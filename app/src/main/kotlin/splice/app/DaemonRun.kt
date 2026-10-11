// NEW: the daemon's run and its ordered stop: the shutdown hook, the startup job beside the signal wait, the halt
// watchdog armed when the signal fires, and the stop, which closes the control server startup owns, waits for startup to
// finish, drains the file lane and releases the daemon lock last.
package splice.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splice.app.daemon.DaemonLock
import splice.core.util.AsyncFileIo
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class DaemonRun(
    private val process: DaemonProcess,
    private val boundary: DaemonBoundary = DaemonBoundary(),
    private val stopDeadlineMs: Long = STOP_DEADLINE_MS,
) {

    /** Registers the JVM shutdown hook and serves until the signal: the whole process-level stop path, as one seam so a
     *  test JVM can drive the real hook and the real ordered stop with a daemon it holds in startup. */
    internal suspend fun serve(daemon: Daemon, lock: DaemonLock, shutdownSignal: CompletableDeferred<Unit>) {
        // `addShutdownHook` takes an unstarted Thread — the one place in this process where the JVM
        // API itself demands the type. It comes from the platform factory rather than an ad-hoc
        // `Thread(...)` so that thread creation has a single seam here as it does in every executor.
        // The hook never stops anything: it asks main to (the signal) and waits, on a plain latch and not in a
        // coroutine, until main's ordered stop has finished. Bounded by the same ladder as the stop itself.
        val stopped = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(
            Executors.defaultThreadFactory().newThread {
                shutdownSignal.complete(Unit)
                stopped.await(stopDeadlineMs + TEARDOWN_TAIL_GRACE_MS, TimeUnit.MILLISECONDS)
            },
        )
        serveUntilShutdown(daemon, lock, shutdownSignal, stopped)
    }

    /** Serves until the signal completes (a control plane that could not bind, or the shutdown hook), then runs the
     *  ordered stop here, in main's own context, and releases the hook waiting on [stopped].
     *
     *  Startup runs beside the wait, not in front of it: a stop signal that arrives while `start` is still going
     *  (the port is bound, a head is still coming up) ends the wait at once, so this `finally` always runs. The halt
     *  watchdog is armed here, when the signal fires, so it bounds a stop that began mid-startup as well. A startup
     *  that fails completes the signal exceptionally, which rethrows here as it did when start ran inline. */
    private suspend fun serveUntilShutdown(
        daemon: Daemon,
        lock: DaemonLock,
        shutdownSignal: CompletableDeferred<Unit>,
        stopped: CountDownLatch,
    ) {
        // Its own scope and a real dispatch: a startup blocked in a call that cannot be cancelled must neither hold
        // main off the wait below nor keep this function from returning once the stop has run.
        val startup = LifecycleScope(ProcessDispatchers().background()).async {
            daemon.start()
            // A control plane that could not bind asks for shutdown before start returns (ControlPlane.start).
            if (!shutdownSignal.isCompleted) process.bootEnded()
        }
        startup.invokeOnCompletion { cause -> if (cause != null) shutdownSignal.completeExceptionally(cause) }
        try {
            shutdownSignal.await()
        } finally {
            try {
                withContext(NonCancellable) {
                    val halt = HaltJvm { Runtime.getRuntime().halt(0) }
                    process.runBoundedTeardown(stopDeadlineMs + TEARDOWN_TAIL_GRACE_MS, halt) {
                        startup.cancel()
                        shutdown(daemon, lock, startup)
                    }
                }
            } finally {
                stopped.countDown()
            }
        }
    }

    // The ordered stop, run once by main after the signal inside runBoundedTeardown. The watchdog is the guarantee
    // SIGTERM lacked: gating JVM exit purely on stop() returning let one wedged head / non-daemon Netty thread turn
    // SIGTERM into a no-op (the operator then reached for SIGKILL, and the racing restart it invited — BS-4).
    // withTimeoutOrNull caps the cooperative stop; halt(0) is the floor for the uninterruptible case a cancel can't
    // reach. The halt floor sits ABOVE the cooperative cap by a grace window: a stop that times out cooperatively at
    // exactly STOP_DEADLINE_MS must still get its drain() + lock.close() tail before the watchdog fires
    // (orchestrator review 2026-07-24 — equal deadlines raced the tail).
    private suspend fun shutdown(daemon: Daemon, lock: DaemonLock, startup: Job) {
        val cooperative = withTimeoutOrNull(stopDeadlineMs) { boundary.runCatchingDaemonBoundary { daemon.stop() } }
        // The lock is the last thing released: a startup still unwinding may yet touch what it acquired. The wait has
        // no bound of its own; the halt watchdog this runs under is the bound.
        startup.join()
        // A stop the deadline cut short left a head start holding the gate, and that start may have finished since:
        // with startup over and the fences set nothing more can open, so the second pass stops what it opened. A
        // stop that finished is `stopped`, and this pass does nothing.
        if (cooperative == null) boundary.runCatchingDaemonBoundary { daemon.stop() }
        // The file lane's flush is the last reportable signal before lock.close() and the halt
        // watchdog: a false means daemon.log / usage / economics writes were lost on the way out.
        if (!AsyncFileIo.drain()) {
            System.err.println("[daemon] file lane did not flush before halt; telemetry writes may be lost\n")
        }
        lock.close()
    }
}
