// NEW: V4-445 — keeps plain `claude` wrapped while the daemon runs. Claude Code's updater re-points
// ~/.local/bin/claude on every release, which replaces the wrap shim; [WrappedHead.reconcile] puts it back, and
// this is what calls it: once at start (an update that landed while the daemon was down), on every change in the
// bin directory (the updater re-points the link), and on a slow tick (a directory that could not be
// watched, an event the platform dropped). Reconcile is idempotent and quiet when nothing changed, so none of
// the three needs to know about the others.
package splice.client.wrap

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.ClosedWatchServiceException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

public class WrapGuard(
    private val head: WrappedHead,
    private val binDir: Path,
    private val log: LogSink,
    private val tickSeconds: Long = SAFETY_TICK_SECONDS,
) : AutoCloseable {

    @Volatile
    private var closed = false
    private val stopped = CountDownLatch(1)
    private var watcher: WatchService? = null
    private val thread = Executors.newSingleThreadExecutor { task ->
        Executors.defaultThreadFactory().newThread(task).apply {
            isDaemon = true
            name = "wrap-guard"
        }
    }

    /** Reconciles once, now, then watches until [close]. Returns at once. */
    public fun start() {
        thread.execute(::run)
    }

    override fun close() {
        closed = true
        stopped.countDown()
        Cancellables.discard(
            Cancellables.runCatchingCleanup { watcher?.close() },
            "closing the watch ends its wait; a failure to close changes nothing about stopping",
        )
        thread.shutdownNow()
    }

    private fun run() {
        register()
        reconcileOnce()
        while (!closed) {
            awaitChange()
            if (closed) return
            // An event and a timeout both reconcile: an event says something moved, a timeout says nothing
            // is known to have, and reconcile answers both the same way.
            reconcileOnce()
        }
    }

    /** Blocks until the bin directory changes or a tick passes. The watch is registered on first use and again
     *  after a failure, so a directory that appears later is picked up on a tick. */
    private fun awaitChange() {
        val service = watcher ?: register()
        if (service == null) {
            stopped.await(tickSeconds, TimeUnit.SECONDS)
            return
        }
        try {
            val key = service.poll(tickSeconds, TimeUnit.SECONDS)
            key?.pollEvents()
            if (key != null && !key.reset()) {
                service.close()
                watcher = null
            }
        } catch (_: ClosedWatchServiceException) {
            watcher = null
            // close() ended the wait: run() sees `closed` next and returns.
            if (!closed) log("[wrap] the watch on $binDir closed unexpectedly\n")
        }
    }

    private fun register(): WatchService? = Cancellables.runCatchingCancellable { watched(SCAN_ATTEMPTS) }.fold(
        onSuccess = { service -> service.also { watcher = it } },
        onFailure = { failure ->
            log("[wrap] cannot watch $binDir (${SafeFailureText.render(failure)}); checking every ${tickSeconds}s\n")
            null
        },
    )

    /** A new watch on [binDir]. On a Mac the JDK's watch polls, and registering scans the folder, so an entry the
     *  updater renames away mid-scan (its `.claude.updating`) fails the scan with NoSuchFileException naming that
     *  entry. That is a rename in flight, not a folder that cannot be watched, so the scan runs again; a folder that
     *  is itself missing still fails at once. */
    private fun watched(attempts: Int): WatchService =
        try {
            binDir.fileSystem.newWatchService().also { service ->
                try {
                    binDir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                } catch (failure: java.io.IOException) {
                    service.close()
                    throw failure
                }
            }
        } catch (vanished: NoSuchFileException) {
            val entry = vanished.file?.let { Path.of(it).parent == binDir } == true
            if (!entry || attempts <= 1) throw vanished
            watched(attempts - 1)
        }

    private fun reconcileOnce() {
        Cancellables.runCatchingCancellable { head.reconcile() }.fold(
            onSuccess = { result ->
                if (result is ReconcileResult.Rewrapped) {
                    log("[wrap] claude now launches ${result.realBinaryPath}, the newest version installed\n")
                }
            },
            onFailure = { failure -> log("[wrap] reconcile failed (${SafeFailureText.render(failure)})\n") },
        )
    }
}

// why: a slow safety net, not the mechanism: the directory watch responds to updater link changes;
// this also covers a directory that could not be watched or an event the platform dropped.
private const val SAFETY_TICK_SECONDS = 60L

// why: one rename in flight costs one rescan; three covers an updater that stages and swaps twice in a row.
private const val SCAN_ATTEMPTS = 3
