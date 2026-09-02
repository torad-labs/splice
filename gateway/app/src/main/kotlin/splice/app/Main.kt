// NEW: process entry (P4-SUP). The ONLY place runBlocking is legal (the walls exempt Main.kt +
// cli/). Acquires the single-flight daemon lock, loads topology, starts the daemon, installs a
// shutdown hook. `splice daemon` is the default; other subcommands route to the CLI (P5-CLI).
package splice.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.config.StatePaths
import splice.core.util.AsyncFileIo
import splice.core.util.DaemonLog
import splice.core.util.LogSink
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

    internal fun runDaemon() {
        val statePaths = StatePaths()
        // JW-01: the boot-failure net exists BEFORE anything that can throw (lock, TOML parse,
        // daemon.start). Both cold-start paths used to launch the JVM with output discarded, so a
        // pre-logger stack trace died in /dev/null and the operator saw only "failed version
        // handshake (got <none>)".
        Thread.setDefaultUncaughtExceptionHandler(bootFailureHandler(statePaths))
        val lock = DaemonLock(statePaths.daemonLockFile)
        if (!lock.tryAcquire()) {
            System.err.println("[daemon] another splice daemon holds the lock — exiting (the winner serves)")
            return
        }
        val topologyPath = TopologyLoader.configPath()
        val loaded = TopologyLoader.loadOrMaterializeWithDigest(topologyPath)
        val topology = loaded.topology
        val distPath = Paths.get(System.getProperty("user.dir"), "..", "webui", "dist", "index.html")
        val log = persistentLogger(statePaths.logsDir)
        // Components that would otherwise fall back to bare stderr (auth providers, ConfigService,
        // ResponsesProvider) default to this sink, so their diagnostics reach daemon.log and therefore
        // /mgmt/logs. Injection still wins where a caller passes its own (wall kt-no-println).
        DaemonLog.install(log)
        val shutdownSignal = CompletableDeferred<Unit>()
        splice.app.cli.InstallCommand().shimStalenessWarning()?.let { log("$it\n") }
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
    // is idempotent (@Synchronized/`stopped`), so a double invocation across the two drivers is safe. The
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
            AsyncFileIo.drain()
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

// The cooperative cap. Its floor — this + TEARDOWN_TAIL_GRACE_MS = 10s — must stay BELOW the CLI's
// graceful stop rung (GRACEFUL_POLLS in cli/DaemonStop.kt, 11s), so a bounded stop is never mistaken
// for a hung one and SIGTERM cannot land mid-tail. The two constants are a pair: change one, check
// the other. (The comment here previously cited a 15s CLI budget that the escalation ladder
// replaced, while the real rung had shrunk to exactly 8s — equal to this cap, zero margin.)
// Also above the head-stop phase's HEAD_STOP_BUDGET_MS so the graceful path wins the common case.
private const val STOP_DEADLINE_MS = 8_000L
private const val TEARDOWN_TAIL_GRACE_MS = 2_000L

// One rolled generation at 64MB caps daemon.log disk at ~128MB — plenty of tail history, bounded.
// Held here so DaemonProcess.persistentLogger keeps the same default the tests pass past.
private const val MAX_LOG_BYTES = 64L * 1024 * 1024
