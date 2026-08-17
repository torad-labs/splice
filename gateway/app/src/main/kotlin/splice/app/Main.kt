// NEW: process entry (P4-SUP). The ONLY place runBlocking is legal (the walls exempt Main.kt +
// cli/). Acquires the single-flight daemon lock, loads topology, starts the daemon, installs a
// shutdown hook. `splice daemon` is the default; other subcommands route to the CLI (P5-CLI).
package splice.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.config.StatePaths
import splice.core.util.AsyncFileIo
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.security.Security
import java.time.LocalTime
import java.time.temporal.ChronoUnit
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
    internal fun runBoundedTeardown(deadlineMs: Long, halt: () -> Unit, teardown: () -> Unit) {
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

    // Timestamps every log line and tees it to a persistent daemon.log (so failures and slow turns
    // survive restarts and are `tail -f`-able) in addition to stderr. The turn path only enqueues an
    // immutable line; the bounded process-wide file lane owns stderr/filesystem latency. The writer
    // stays OPEN for the daemon's lifetime. A failed write drops the writer so the
    // next line reopens. SIZE-ROTATION (audit 2026-07-18: daemon.log grew forever, ~1KB/turn): at
    // MAX_LOG_BYTES the file is moved to daemon.log.1 (one generation kept) and a fresh file opened.
    //
    // LINE TERMINATION IS NORMALIZED HERE, not trusted to callers (review of PR #62, 2026-07-27).
    // This sink used to `print`/`write` the message verbatim, so a trailing "\n" was part of every
    // caller's string by convention. That convention is invisible at the call site and silently
    // breaks whoever forgets: the kt-no-println conversion moved 14 sites off System.err.println —
    // which appends the newline for you — onto this sink, and their entries merged into one run-on
    // line in daemon.log. /mgmt/logs splits on "\n" (ControlServer.logsJson), so the endpoint this
    // whole change exists to feed emitted concatenated garbage. Exactly one terminator is appended
    // here, which is a no-op for the callers that already pass one and makes the class unrepeatable.
    internal fun persistentLogger(logsDir: Path, maxBytes: Long = MAX_LOG_BYTES): LogSink {
        Cancellables.runCatchingCancellable { Files.createDirectories(logsDir) }
        val file = logsDir.resolve("daemon.log")
        val rolled = logsDir.resolve("daemon.log.1")
        var writer: java.io.Writer? = null
        var written = Cancellables
            .runCatchingCancellable { if (Files.exists(file)) Files.size(file) else 0L }
            .getOrDefault(0L)
        return LogSink { msg ->
            val line = "[${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)}] ${msg.trimEnd('\n')}\n"
            AsyncFileIo.submit {
                System.err.print(line)
                Cancellables.runCatchingCancellable {
                    if (written >= maxBytes) {
                        writer?.close()
                        Files.move(file, rolled, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        writer = null
                        written = 0L
                    }
                    val w = writer ?: Files.newBufferedWriter(file, CREATE, APPEND).also { writer = it }
                    w.write(line)
                    w.flush()
                    written += line.toByteArray(Charsets.UTF_8).size
                }.onFailure { failure ->
                    Cancellables.runCatchingCancellable { writer?.close() }
                    writer = null
                    // SH-14: a failed rotate used to leave `written` >= the cap forever — every later
                    // line re-entered the rotate branch, threw BEFORE reaching newBufferedWriter, and
                    // daemon.log went silent permanently. Reconcile from disk so the next line
                    // self-corrects, and say so on stderr (the one lane still alive here).
                    written = Cancellables.runCatchingCancellable { if (Files.exists(file)) Files.size(file) else 0L }
                        .getOrDefault(0L)
                    System.err.print("[daemon-log] write/rotate failed ($failure) — size reconciled to $written\n")
                }
            }
        }
    }

    /** JW-01: the last-resort boot net. Writes SYNCHRONOUSLY — the async file lane is a daemon
     *  thread that dies with the JVM, and this fires when the JVM is dying. No catch clause on the
     *  boot path itself (an uncaught-exception handler needs none), so the cancellation and
     *  broad-catch walls stay untouched. Covers runtime uncaught throwables on other threads too —
     *  a net, not a boot-only special case. */
    internal fun bootFailureHandler(statePaths: StatePaths): Thread.UncaughtExceptionHandler =
        Thread.UncaughtExceptionHandler { thread, e ->
            val line = "[${LocalTime.now().truncatedTo(ChronoUnit.SECONDS)}] [daemon] UNCAUGHT on " +
                "${thread.name}: ${e.stackTraceToString()}\n"
            System.err.print(line)
            Cancellables.runCatchingCancellable {
                Files.createDirectories(statePaths.logsDir)
                Files.writeString(statePaths.logsDir.resolve("daemon.log"), line, CREATE, APPEND)
            }
        }
}

// The cooperative cap. Its floor — this + TEARDOWN_TAIL_GRACE_MS = 10s — must stay BELOW the CLI's
// graceful stop rung (ControlPlaneClient.GRACEFUL_POLLS, 11s), so a bounded stop is never mistaken
// for a hung one and SIGTERM cannot land mid-tail. The two constants are a pair: change one, check
// the other. (The comment here previously cited a 15s CLI budget that the escalation ladder
// replaced, while the real rung had shrunk to exactly 8s — equal to this cap, zero margin.)
// Also above the head-stop phase's HEAD_STOP_BUDGET_MS so the graceful path wins the common case.
private const val STOP_DEADLINE_MS = 8_000L
private const val TEARDOWN_TAIL_GRACE_MS = 2_000L

// One rolled generation at 64MB caps daemon.log disk at ~128MB — plenty of tail history, bounded.
private const val MAX_LOG_BYTES = 64L * 1024 * 1024
