// NEW: process entry (P4-SUP). `main` is suspend, so no runBlocking exists anywhere. Acquires the
// single-flight daemon lock, loads topology, starts the daemon, installs a
// shutdown hook. `splice daemon` is the default; other subcommands route to the CLI (P5-CLI).
package splice.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.daemon.DaemonLock
import splice.app.daemon.DaemonLockWait
import splice.app.daemon.LockOutcome
import splice.client.wrap.WrapGuard
import splice.client.wrap.WrappedHead
import splice.core.config.InstallPaths
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.AsyncFileIo
import splice.core.util.DaemonLog
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.launch.install.InstallShim
import splice.lifecycle.start.BOOT_LOG_FLAG
import splice.topology.TopologyLoader
import splice.topology.TopologyStatePaths
import java.nio.file.Path
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

public suspend fun main(args: Array<String>) {
    // Kill JVM negative-DNS caching BEFORE any lookup (kimi 07:00 burst, 2026-07-18): the JVM
    // caches a FAILED lookup for 10s by default, so one resolver timeout for api.kimi.com poisoned
    // every following request — 37 turn failures from one blip, including 5ms "failures" that never
    // touched the network. A long-lived proxy must re-ask on each miss; successful-lookup caching
    // (30s) stays as is. Retry backoff (200-800ms) only works against real lookups, not a poison
    // window three times its whole budget. Pin it explicitly too — the positive TTL's vendor
    // default is unspecified/-1 without a SecurityManager, so leaving it implicit is the same
    // latent-default trap G10 (stale shim) already burned once.
    Security.setProperty("networkaddress.cache.negative.ttl", "0")
    Security.setProperty("networkaddress.cache.ttl", "30")
    // V4-74: THE DAEMON'S ORDERED STOP IS THE ONLY SHUTDOWN OWNER. Ktor's EmbeddedServer registers
    // its OWN JVM shutdown hook per engine, and on SIGTERM those hooks run CONCURRENTLY with the
    // hook below (shutdown -> daemon.stop -> stopHeads -> HeadServer.stopLocked): engine.stop
    // disposes the application scope and cancels every call handler, so the in-flight SSE write
    // fails and the turn ends as a conn-reset AFTER content — which Claude Code does not retry, it
    // prints "API Error: Connection lost mid-response". MEASURED on the operator's session: two
    // restarts, both cutting a mid-stream turn in the same second, with no stop: draining line ever.
    //
    // The switch, read from the ktor-server-core-jvm 3.5.2 bytecode rather than guessed:
    // ShutdownHookJvmKt's static initializer computes SHUTDOWN_HOOK_ENABLED as
    // System.getProperty("io.ktor.server.engine.ShutdownHook", "true") == "true", and
    // ShutdownHookKt.addShutdownHook (called by EmbeddedServer.start) reads that flag and returns
    // WITHOUT registering the hook when it is false. Two properties of that read matter here: it is
    // an EQUALITY test against the literal "true", so "false" disables it; and the value is cached
    // in a static final, so it must be set BEFORE the class is first loaded — which is here, before
    // any engine exists. One property covers every embeddedServer in the process, head engines AND
    // ControlServer's, which is why this is a process-wide line and not a per-engine flag.
    when (args.firstOrNull()) {
        null, "daemon" -> DaemonProcess(args.toList()).runDaemon()
        "start" -> LifecycleWiring.startThroughUnit()?.let(::exitProcess) ?: DaemonProcess(args.toList()).runDaemon()
        else -> exitProcess(splice.app.cli.Cli().runCli(args))
    }
}

/** The daemon process's own scaffolding — boot, bounded teardown, and the two log sinks that must
 *  exist before anything that can throw. A constructed collaborator rather than a set of free
 *  functions (Kotlin style law, 2026-08-15); `fun main` above stays top-level because the JVM
 *  entry point must be static, which the law exempts. */
internal class DaemonProcess(
    private val args: List<String> = emptyList(),
    private val boundary: DaemonBoundary = DaemonBoundary(),
) {

    internal suspend fun runDaemon() {
        armShutdownOwnership()
        // The BOOTSTRAP state paths: the crash log needs a path before anything can throw, and
        // [daemon].state_dir cannot be known until the topology below has parsed — so the net is
        // armed against the default and the override is applied immediately after the parse.
        val bootstrapPaths = StatePaths()
        // JW-01: the boot-failure net exists BEFORE anything that can throw (lock, TOML parse,
        // daemon.start). Both cold-start paths used to launch the JVM with output discarded, so a
        // pre-logger stack trace died in /dev/null and the operator saw only "failed version
        // handshake (got <none>)".
        Thread.setDefaultUncaughtExceptionHandler(bootFailureHandler(bootstrapPaths))
        val start = prepare()
        val topology = start.loaded.topology
        val statePaths = start.statePaths
        val lock = DaemonLock(statePaths.daemonLockFile)
        val controlPort = splice.app.cli.AdminSupport.controlPort(topology)
        val lockWait = DaemonLockWait()
        val lost = LostLock(controlPort, lockWait.windowMs())
        if (lockLost(start, lockWait.acquire(lock, controlPort), lost, TerminalOutput(System.err::println))) return
        val log = persistentLogger(statePaths.logsDir)
        // Components that would otherwise fall back to bare stderr (auth providers, ConfigService,
        // ResponsesProvider) default to this sink, so their diagnostics reach daemon.log and therefore
        // /mgmt/logs. Injection still wins where a caller passes its own (wall kt-no-println).
        DaemonLog.install(log)
        start.ownerOnlyLines.forEach { log(it) }
        val shutdownSignal = CompletableDeferred<Unit>()
        InstallShim().shimStalenessWarning(EnvReader(System::getenv))?.let { log("$it\n") }
        val daemon = Daemon(
            topology,
            statePaths,
            log = log,
            shutdownDaemon = { shutdownSignal.complete(Unit) },
            // JW-04: the booted config identity, published on /health so an edited-but-inert
            // splice.toml is visible to the shim, doctor, and the dashboard.
            topologyDigest = start.loaded.digest,
            topologyPath = start.topologyPath,
        )

        // `addShutdownHook` takes an unstarted Thread — the one place in this process where the JVM
        // API itself demands the type. It comes from the platform factory rather than an ad-hoc
        // `Thread(...)` so that thread creation has a single seam here as it does in every executor.
        // The hook never stops anything: it asks main to (the signal) and waits, on a plain latch and not in a
        // coroutine, until main's ordered stop has finished. Bounded by the same ladder as the stop itself.
        val stopped = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(
            Executors.defaultThreadFactory().newThread {
                shutdownSignal.complete(Unit)
                stopped.await(STOP_DEADLINE_MS + TEARDOWN_TAIL_GRACE_MS, TimeUnit.MILLISECONDS)
            },
        )
        // V4-445: keeps a wrapped plain `claude` wrapped across Claude Code's own updates. Production only: it
        // watches the real ~/.local/bin, which a test daemon must never do.
        WrapGuard(WrappedHead(UserHome.dir()), InstallPaths().binDir, log).use { guard ->
            guard.start()
            serveUntilShutdown(daemon, lock, shutdownSignal, stopped)
        }
    }

    /** V4-74: THE DAEMON'S ORDERED STOP IS THE ONLY SHUTDOWN OWNER, and this is where that is armed.
     *
     *  Ktor's EmbeddedServer registers its OWN JVM shutdown hook per engine, and on SIGTERM those
     *  hooks run CONCURRENTLY with the hook registered below (shutdown -> daemon.stop -> stopHeads ->
     *  HeadServer.stopLocked): engine.stop disposes the application scope and cancels every call
     *  handler, so an in-flight SSE write fails and the turn ends as a conn-reset AFTER content —
     *  which Claude Code does not retry, it prints "API Error: Connection lost mid-response".
     *  MEASURED on the operator's own session: two restarts, each cutting a mid-stream turn in the
     *  same second, with no stop: draining line ever reaching the log.
     *
     *  The switch, read from the ktor-server-core-jvm 3.5.2 bytecode rather than guessed:
     *  ShutdownHookJvmKt's static initializer computes SHUTDOWN_HOOK_ENABLED as
     *  System.getProperty("io.ktor.server.engine.ShutdownHook", "true") == "true", and
     *  ShutdownHookKt.addShutdownHook — called by EmbeddedServer.start — reads that flag and returns
     *  WITHOUT registering the hook when it is false. Two properties of that read decide where this
     *  call has to live: it is an EQUALITY test against the literal "true", so "false" disables it;
     *  and the value is cached in a static final, so it must be set BEFORE the class is first
     *  loaded, which is why this runs at the top of runDaemon — ahead of the lock, the topology read
     *  and every engine. ONE property covers every embeddedServer in the process, the head engines
     *  AND ControlServer's, which is why it is a process-wide line and not a per-engine flag.
     *
     *  Callable on its own so the boot seam is testable: see DaemonStopBudgetTest. */
    internal fun armShutdownOwnership() {
        System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
    }

    /** Serves until the signal completes (a control plane that could not bind, or the shutdown hook), then runs the
     *  ordered stop here, in main's own context, and releases the hook waiting on [stopped]. */
    private suspend fun serveUntilShutdown(
        daemon: Daemon,
        lock: DaemonLock,
        shutdownSignal: CompletableDeferred<Unit>,
        stopped: CountDownLatch,
    ) {
        try {
            daemon.start()
            // A control plane that could not bind asks for shutdown before start returns (ControlPlane.start).
            if (!shutdownSignal.isCompleted) bootEnded()
            shutdownSignal.await()
        } finally {
            try {
                shutdown(daemon, lock)
            } finally {
                stopped.countDown()
            }
        }
    }

    // Bounded shutdown, run once by main after the signal (the SIGTERM hook only completes that signal). The
    // watchdog is the guarantee SIGTERM lacked: gating JVM exit purely on stop() returning let one wedged
    // head / non-daemon Netty thread turn SIGTERM into a no-op (the operator then reached for SIGKILL,
    // and the racing restart it invited — BS-4). withTimeoutOrNull caps the cooperative stop; halt(0) is
    // the floor for the uninterruptible case a cancel can't reach.
    private suspend fun shutdown(daemon: Daemon, lock: DaemonLock) {
        // The halt floor sits ABOVE the cooperative cap by a grace window: a stop that times out
        // cooperatively at exactly STOP_DEADLINE_MS must still get its drain() + lock.close() tail
        // before the watchdog fires (orchestrator review 2026-07-24 — equal deadlines raced the tail).
        runBoundedTeardown(STOP_DEADLINE_MS + TEARDOWN_TAIL_GRACE_MS, { Runtime.getRuntime().halt(0) }) {
            withTimeoutOrNull(STOP_DEADLINE_MS) { boundary.runCatchingDaemonBoundary { daemon.stop() } }
            // The file lane's flush is the last reportable signal before lock.close() and the halt
            // watchdog: a false means daemon.log / usage / economics writes were lost on the way out.
            if (!AsyncFileIo.drain()) {
                System.err.println("[daemon] file lane did not flush before halt; telemetry writes may be lost\n")
            }
            lock.close()
        }
    }

    // Run [teardown] under a hard deadline: a daemon watchdog thread [halt]s the JVM if teardown overruns
    // [deadlineMs]. A cancel (withTimeoutOrNull) can't kill a thread stuck in uninterruptible blocking work
    // (a wedged engine stop), so halt(0) is the floor that guarantees termination. On a clean finish the
    // watchdog is disarmed via [halted] so halt never fires. [halt] is injected so tests exercise both paths.
    internal suspend fun runBoundedTeardown(deadlineMs: Long, halt: HaltJvm, teardown: Teardown) {
        val halted = AtomicBoolean(false)
        // A named single-thread scheduler holding ONE delayed task, not a raw thread parked in
        // Thread.sleep: same daemon-ness (the JVM never waits on it), same one-shot firing at
        // [deadlineMs], and the disarm is now structural — shutdownNow() drops the pending task
        // instead of leaving a thread asleep until the deadline to discover the CAS already lost.
        // The CAS stays as the race floor for the case where the task is already running.
        val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "splice-teardown-watchdog"
                isDaemon = true
            }
        }
        watchdog.schedule(
            Runnable {
                if (halted.compareAndSet(false, true)) {
                    System.err.println("[daemon] stop exceeded ${deadlineMs}ms; halting")
                    halt()
                }
            },
            deadlineMs,
            TimeUnit.MILLISECONDS,
        )
        teardown()
        halted.set(true)
        watchdog.shutdownNow()
    }

    /** V4-258: a launcher that sends this daemon's stderr into daemon-boot.log passes [BOOT_LOG_FLAG],
     *  and daemon.log's lines then stay out of it once the daemon is up. V4-353: until then they reach it
     *  too, because the boot log is what a cold start prints when the daemon never answers. */
    internal fun persistentLogger(logsDir: Path, maxBytes: Long = MAX_LOG_BYTES): LogSink {
        val stderrIsBootLog = BOOT_LOG_FLAG in args
        return boundary.persistentLogger(logsDir, maxBytes, echoToStderr = StderrEcho { !stderrIsBootLog || booting })
    }

    /** V4-353: the daemon is up (its control plane bound); the boot log's copy of daemon.log ends here. */
    internal fun bootEnded() {
        booting = false
    }

    @Volatile
    private var booting = true

    internal fun bootFailureHandler(statePaths: StatePaths): Thread.UncaughtExceptionHandler =
        boundary.bootFailureHandler(statePaths)

    /** Whether the start lost the lock to another process; when it did, why it exits goes to [err], then
     *  what [start] made owner-only before the lock. V4-284: those lines waited for the logger, which
     *  only a winner makes, so a loser that changed a file's mode never said so. */
    internal fun lockLost(start: StartState, outcome: LockOutcome, lost: LostLock, err: TerminalOutput): Boolean {
        val why = when (outcome) {
            LockOutcome.WON -> return false
            LockOutcome.PEER_SERVING ->
                "[daemon] another splice daemon serves on :${lost.controlPort}; exiting (the winner serves)"
            LockOutcome.EXPIRED ->
                "[daemon] the daemon lock stayed held for ${lost.windowMs}ms by a process that is not " +
                    "serving on :${lost.controlPort}; exiting. Find that process (`splice doctor`) and retry"
        }
        err.line(why)
        start.ownerOnlyLines.forEach(err::line)
        return true
    }

    /** The start up to its lock (V4-280: split out of [runDaemon] so a test drives the steps the start
     *  itself runs; a start that dropped a secure call used to pass every test). [env] is the process's
     *  environment, a test's own map under test. */
    internal fun prepare(env: EnvReader = EnvReader(System::getenv)): StartState {
        // The topology is read BEFORE the lock so a loser can health-check the winner's control
        // port (DaemonLockWait): reading is what the winner does next anyway, and a materialized
        // example is idempotent between the two.
        val topologyPath = TopologyLoader.configPath(env)
        val loaded = TopologyLoader.loadOrMaterializeWithDigest(topologyPath)
        // V4-109: [daemon].state_dir is HONOURED from here on. It could not be applied before this
        // point: the boot-failure net is armed at the top with a StatePaths because it must exist
        // before anything that can throw (JW-01), and the state dir is what the lock, config.json
        // and the per-head stat files are rooted at — so the override is resolved the moment the
        // topology has parsed and then used by EVERY later step. The one visible consequence of
        // that ordering is stated rather than left to be discovered: an overriding daemon moves its
        // state but not the crash log, which the net already captured against the default.
        // The same resolver every CLI reader of this state uses (TopologyStatePaths), so `splice
        // restart`, doctor and logs look where this daemon writes.
        val statePaths = TopologyStatePaths(env).of(loaded.topology)
        // v0.4.0: the state splice owns is owner-only BEFORE the first write into it (the lock), and
        // (V4-278) so are splice.toml and its backups, which can hold header secrets and which an older
        // splice left at the umask. What they say is logged once the logger exists.
        val ownerOnlyLines = secureStateDirs(statePaths) + secureConfig(topologyPath)
        return StartState(topologyPath, loaded, statePaths, ownerOnlyLines)
    }

    internal fun secureStateDirs(statePaths: StatePaths): List<String> = boundary.secureStateDirs(statePaths)

    internal fun secureConfig(configPath: Path): List<String> = boundary.secureConfig(configPath)
}

/** What a start that lost the lock says about the winner: the port it serves on, and how long the
 *  lock was waited for. */
internal data class LostLock(val controlPort: Int, val windowMs: Long)

/** What the start knows when it takes the lock: the topology it read and where, where its state
 *  lives, and the owner-only step's lines, logged once the logger exists. */
internal data class StartState(
    val topologyPath: Path,
    val loaded: TopologyLoader.LoadedTopology,
    val statePaths: StatePaths,
    val ownerOnlyLines: List<String>,
)

// The cooperative cap. Its floor — this + TEARDOWN_TAIL_GRACE_MS = 57s — must stay BELOW the CLI's
// graceful stop rung (GRACEFUL_POLLS in features/lifecycle's DaemonStop.kt, 60s), so a bounded stop
// is never mistaken for a hung one and SIGTERM cannot land mid-tail. The two constants are a pair:
// change one, check the other. (The comment here previously cited a 15s CLI budget that the
// escalation ladder replaced, while the real rung had shrunk to exactly 8s — equal to this cap, zero margin.)
// Also above the head-stop phase's HEAD_STOP_BUDGET_MS so the graceful path wins the common case.
//
// V4-74 — THE WHOLE LADDER, innermost first, because raising one link alone is DEAD CODE:
//   drain 45s (HeadServer) < head budget 50s (HeadShutdown) < this cap 55s
//   < halt floor 57s (this + TEARDOWN_TAIL_GRACE_MS) < CLI rung 60s (DaemonStop)
//   < systemd TimeoutStopSec 90s (the external bound).
// The reason the drain had to grow is a measured turn length: a restart used to cancel every
// in-flight turn at 8s, and a deepseek turn runs 7 to 16s. DaemonStopBudgetTest pins the ordering
// so the next person who raises one link gets a red instead of a silently ineffective constant.
internal const val STOP_DEADLINE_MS = 55_000L
internal const val TEARDOWN_TAIL_GRACE_MS = 2_000L

// One rolled generation at 64MB caps daemon.log disk at ~128MB — plenty of tail history, bounded.
// Held here so DaemonProcess.persistentLogger keeps the same default the tests pass past.
private const val MAX_LOG_BYTES = 64L * 1024 * 1024
