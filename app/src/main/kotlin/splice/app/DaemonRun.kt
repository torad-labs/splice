// NEW: the daemon's run and its ordered stop: the shutdown hook, the startup job beside the signal wait, the halt
// watchdog armed when the signal fires, and the stop, which closes the control server startup owns, waits for startup to
// finish, drains the file lane and releases the daemon lock last.
//
// #523: a second signal, the hand-over. The next daemon asks this one to retire; it stops taking requests, releases
// the lock so the next daemon boots, and keeps running until the answers it was in the middle of have ended. Then,
// or at a stop signal, the ordered stop above runs as it always has.
package splice.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
    internal suspend fun serve(
        daemon: Daemon,
        lock: DaemonLock,
        shutdownSignal: CompletableDeferred<Unit>,
        retireSignal: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
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
        serveUntilShutdown(daemon, lock, DaemonSignals(shutdownSignal, retireSignal), stopped)
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
        signals: DaemonSignals,
        stopped: CountDownLatch,
    ) {
        val shutdownSignal = signals.shutdown
        // Its own scope and a real dispatch: a startup blocked in a call that cannot be cancelled must neither hold
        // main off the wait below nor keep this function from returning once the stop has run.
        val startup = LifecycleScope(ProcessDispatchers().background()).async {
            daemon.start()
            // A control plane that could not bind asks for shutdown before start returns (ControlPlane.start).
            if (!shutdownSignal.isCompleted) process.bootEnded()
        }
        startup.invokeOnCompletion { cause -> if (cause != null) shutdownSignal.completeExceptionally(cause) }
        try {
            if (signals.retireAsked()) handOver(daemon, lock, startup, shutdownSignal)
        } finally {
            try {
                withContext(NonCancellable) {
                    val halt = HaltJvm { exitCode ->
                        // The lane is a daemon thread apart from whatever wedged the stop: give the lines already queued a
                        // short window to land whole, then end the process.
                        AsyncFileIo.drain(HALT_DRAIN_MS)
                        Runtime.getRuntime().halt(exitCode)
                    }
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
    // withTimeoutOrNull caps the cooperative stop; halt is the floor for the uninterruptible case a cancel can't
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
        release(lock)
    }

    /** #523: this daemon hands over to the next one and finishes what it was answering. Startup is waited for first:
     *  the request arrives over the control server, which startup opens last, so the wait is over or nearly so. The
     *  lock is released once the heads' books are written and the control server has stopped taking connections, and
     *  the next daemon, which has been waiting on it, boots from there. A stop signal ends the wait for the kept answers, and the
     *  ordered stop that follows cuts what is still running. */
    private suspend fun handOver(
        daemon: Daemon,
        lock: DaemonLock,
        startup: Job,
        shutdownSignal: CompletableDeferred<Unit>,
    ) {
        startup.join()
        boundary.runCatchingDaemonBoundary { daemon.retire() }
        release(lock)
        coroutineScope {
            val finishing = launch { boundary.runCatchingDaemonBoundary { daemon.finish(RETIRED_FINISH_MS) } }
            select<Unit> {
                shutdownSignal.onAwait { }
                finishing.onJoin { }
            }
            finishing.cancel()
        }
    }

    /** The lock is the last thing released, after the file lane's flush: a daemon waiting on it reads what this one
     *  wrote. That flush is the last reportable signal before the halt watchdog: a false means daemon.log, usage or
     *  economics writes were lost on the way out. Called at a hand-over and again at the stop, where it finds the
     *  lock already released. */
    private fun release(lock: DaemonLock) {
        if (!AsyncFileIo.drain()) {
            System.err.println("[daemon] file lane did not flush before halt; telemetry writes may be lost\n")
        }
        lock.close()
    }
}

/** The two things a serving daemon waits for: a stop, and (#523) the next daemon's request to hand over. */
internal class DaemonSignals(val shutdown: CompletableDeferred<Unit>, private val retire: CompletableDeferred<Unit>) {
    /** Waits for whichever comes first, and says whether it was the hand-over. A startup that failed completes
     *  [shutdown] exceptionally, which rethrows here as its await did. */
    suspend fun retireAsked(): Boolean = select {
        shutdown.onAwait { false }
        retire.onAwait { true }
    }
}

// How long a daemon that handed over waits for the answers it kept. A long answer on the largest models runs for
// several minutes, and one that is still running after this is cut with the restart line, as every running turn
// was before #523.
internal const val RETIRED_FINISH_MS = 15 * 60_000L
