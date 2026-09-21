// NEW: process entry (P4-SUP). The ONLY place runBlocking is legal (the walls exempt Main.kt +
// cli/). Acquires the single-flight daemon lock, loads topology, starts the daemon, installs a
// shutdown hook. `splice daemon` is the default; other subcommands route to the CLI (P5-CLI).
package splice.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.daemon.DaemonLock
import splice.app.daemon.DaemonLockWait
import splice.app.daemon.LockOutcome
import splice.app.daemon.TopologyLoader
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.AsyncFileIo
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.Security
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
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
        null, "daemon", "start" -> DaemonProcess().runDaemon()
        else -> exitProcess(splice.app.cli.Cli().runCli(args))
    }
}

/** The daemon process's own scaffolding — boot, bounded teardown, and the two log sinks that must
 *  exist before anything that can throw. A constructed collaborator rather than a set of free
 *  functions (Kotlin style law, 2026-08-15); `fun main` above stays top-level because the JVM
 *  entry point must be static, which the law exempts. */
internal class DaemonProcess {

    private val boundary = DaemonBoundary()

    /** V4-109: the `[daemon].state_dir` override, resolved once the topology has parsed — the
     *  behaviour the key promised and never had (it was parsed, echoed by the doctor, and read by
     *  nothing). A value that cannot be used leaves the default in place rather than failing the
     *  boot: the key was INERT before this row, so a value operators were free to write must not
     *  become a startup failure now that it means something (NEVER-BELOW-STATUS-QUO). Blank is
     *  treated as absent for the same reason. */
    private fun statePathsFor(topology: Topology, fallback: StatePaths): StatePaths {
        val declared = topology.daemon.stateDir?.takeIf { it.isNotBlank() } ?: return fallback
        // An unusable declared state_dir falls back to the default BY DESIGN (the function's KDoc):
        // a path the JVM cannot parse is dropped, not a swallowed failure, and the operator still
        // sees the dropped override through the doctor row V4-110 adds. The named catch keeps the
        // same disposition without runCatching swallowing a coroutine cancellation on this boot path.
        //
        // V4-122 item 7, the disposition this site owed: the parameter is `_` because the exception
        // is DELIBERATELY not used — that is detekt's own allowance (allowedExceptionNameRegex) and
        // this tree's idiom at 67 other sites, not a per-site suppression. Binding it to a name and
        // then ignoring it would claim a use that does not exist, and @Suppress is refused by this
        // repo's wall in favour of expressing the intent in the code.
        val path = try {
            Paths.get(declared)
        } catch (_: InvalidPathException) {
            return fallback
        }
        return StatePaths(baseOverride = path)
    }

    internal fun runDaemon() {
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
        // The topology is read BEFORE the lock so a loser can health-check the winner's control
        // port (DaemonLockWait): reading is what the winner does next anyway, and a materialized
        // example is idempotent between the two.
        val topologyPath = TopologyLoader.configPath()
        val loaded = TopologyLoader.loadOrMaterializeWithDigest(topologyPath)
        val topology = loaded.topology
        // V4-109: [daemon].state_dir is HONOURED from here on. It could not be applied before this
        // point: the boot-failure net is armed at the top with a StatePaths because it must exist
        // before anything that can throw (JW-01), and the state dir is what the lock, config.json
        // and the per-head stat files are rooted at — so the override is resolved the moment the
        // topology has parsed and then used by EVERY later step. The one visible consequence of
        // that ordering is stated rather than left to be discovered: an overriding daemon moves its
        // state but not the crash log, which the net already captured against the default.
        val statePaths = statePathsFor(topology, bootstrapPaths)
        val lock = DaemonLock(statePaths.daemonLockFile)
        val controlPort = splice.app.cli.AdminSupport.controlPort(topology)
        val lockWait = DaemonLockWait()
        when (lockWait.acquire(lock, controlPort)) {
            LockOutcome.WON -> Unit
            LockOutcome.PEER_SERVING -> {
                System.err.println(
                    "[daemon] another splice daemon serves on :$controlPort — exiting (the winner serves)",
                )
                return
            }
            LockOutcome.EXPIRED -> {
                System.err.println(
                    "[daemon] the daemon lock stayed held for ${lockWait.windowMs()}ms by a process that is not " +
                        "serving on :$controlPort — exiting; find that process (`splice doctor`) and retry",
                )
                return
            }
        }
        val distPath = Paths.get(System.getProperty("user.dir"), "..", "webui", "dist", "index.html")
        val log = persistentLogger(statePaths.logsDir)
        // Components that would otherwise fall back to bare stderr (auth providers, ConfigService,
        // ResponsesProvider) default to this sink, so their diagnostics reach daemon.log and therefore
        // /mgmt/logs. Injection still wins where a caller passes its own (wall kt-no-println).
        DaemonLog.install(log)
        val shutdownSignal = CompletableDeferred<Unit>()
        splice.app.cli.install.InstallCommand().shimStalenessWarning()?.let { log("$it\n") }
        val daemon = Daemon(
            topology,
            statePaths,
            DashboardHtml().source(distPath),
            log = log,
            shutdownDaemon = { shutdownSignal.complete(Unit) },
            // JW-04: the booted config identity, published on /health so an edited-but-inert
            // splice.toml is visible to the shim, doctor, and the dashboard.
            topologyDigest = loaded.digest,
            topologyPath = topologyPath,
        )

        // `addShutdownHook` takes an unstarted Thread — the one place in this process where the JVM
        // API itself demands the type. It comes from the platform factory rather than an ad-hoc
        // `Thread(...)` so that thread creation has a single seam here as it does in every executor.
        Runtime.getRuntime().addShutdownHook(
            Executors.defaultThreadFactory().newThread { shutdown(daemon, lock) },
        )
        serveUntilShutdown(daemon, lock, shutdownSignal)
    }

    /** The blocking serve loop, PRIVATE by law: wall kt-no-runblocking-exported-bridge lets Main.kt
     *  CALL runBlocking at process entry but never EXPORT a blocking bridge, and relocating these
     *  functions into a class turned the old file-private `runDaemon` into a member. The blocking
     *  body therefore lives here, one level below the member `main` dispatches to. */
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

    private fun serveUntilShutdown(
        daemon: Daemon,
        lock: DaemonLock,
        shutdownSignal: CompletableDeferred<Unit>,
    ) {
        runBlocking {
            try {
                daemon.start()
                shutdownSignal.await()
            } finally {
                shutdown(daemon, lock)
            }
        }
    }

    // Bounded shutdown shared by BOTH drivers (the SIGTERM hook and the run-loop finally). daemon.stop()
    // is idempotent (`stopLock` Mutex + `stopped`), so a double invocation across the two drivers is safe. The
    // watchdog is the guarantee SIGTERM lacked: gating JVM exit purely on stop() returning let one wedged
    // head / non-daemon Netty thread turn SIGTERM into a no-op (the operator then reached for SIGKILL,
    // and the racing restart it invited — BS-4). withTimeoutOrNull caps the cooperative stop; halt(0) is
    // the floor for the uninterruptible case a cancel can't reach.
    private fun shutdown(daemon: Daemon, lock: DaemonLock) {
        // The halt floor sits ABOVE the cooperative cap by a grace window: a stop that times out
        // cooperatively at exactly STOP_DEADLINE_MS must still get its drain() + lock.close() tail
        // before the watchdog fires (orchestrator review 2026-07-24 — equal deadlines raced the tail).
        runBoundedTeardown(STOP_DEADLINE_MS + TEARDOWN_TAIL_GRACE_MS, { Runtime.getRuntime().halt(0) }) {
            runBlocking {
                withTimeoutOrNull(STOP_DEADLINE_MS) { boundary.runCatchingDaemonBoundary { daemon.stop() } }
            }
            // The file lane's flush is the last reportable signal before lock.close() and the halt
            // watchdog: a false means daemon.log / usage / economics writes were lost on the way out.
            if (!AsyncFileIo.drain()) {
                System.err.println("[daemon] file lane did not flush before halt — telemetry writes may be lost\n")
            }
            lock.close()
        }
    }

    // Run [teardown] under a hard deadline: a daemon watchdog thread [halt]s the JVM if teardown overruns
    // [deadlineMs]. A cancel (withTimeoutOrNull) can't kill a thread stuck in uninterruptible blocking work
    // (a wedged engine stop), so halt(0) is the floor that guarantees termination. On a clean finish the
    // watchdog is disarmed via [halted] so halt never fires. [halt] is injected so tests exercise both paths.
    internal fun runBoundedTeardown(deadlineMs: Long, halt: HaltJvm, teardown: Teardown) {
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
                    System.err.println("[daemon] stop exceeded ${deadlineMs}ms — halting")
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

    internal fun persistentLogger(logsDir: Path, maxBytes: Long = MAX_LOG_BYTES): LogSink =
        boundary.persistentLogger(logsDir, maxBytes)

    internal fun bootFailureHandler(statePaths: StatePaths): Thread.UncaughtExceptionHandler =
        boundary.bootFailureHandler(statePaths)
}

// The cooperative cap. Its floor — this + TEARDOWN_TAIL_GRACE_MS = 57s — must stay BELOW the CLI's
// graceful stop rung (GRACEFUL_POLLS in cli/DaemonStop.kt, 60s), so a bounded stop is never mistaken
// for a hung one and SIGTERM cannot land mid-tail. The two constants are a pair: change one, check
// the other. (The comment here previously cited a 15s CLI budget that the escalation ladder
// replaced, while the real rung had shrunk to exactly 8s — equal to this cap, zero margin.)
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
