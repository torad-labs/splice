package splice.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Keeps history encoding and source replies from simultaneously reserving their large workspaces. */
public object PerfWorkPhase {
    private val permits = Semaphore(QUERY_PHASES)
    private val checkpoints = Mutex()
    private val exportMonitor = java.lang.Object()
    private var activeExports = 0
    private var checkpointWaiters = 0
    private var checkpointActive = false
    private var exportChanged = CompletableDeferred<Unit>()

    /** Hosts await inside their existing request deadline and retain ownership through reply release. */
    public suspend fun query(): Lease {
        permits.acquire()
        return Lease(1)
    }

    /** Await before allocating a snapshot or submitting work; never call from the shared file-I/O lane. */
    public suspend fun checkpoint(): Lease {
        synchronized(exportMonitor) {
            checkpointWaiters++
            signalExportChange()
        }
        try {
            return checkpoints.withLock {
                enterCheckpointPhase()
                var acquired = 0
                var transferPhase = true
                try {
                    repeat(QUERY_PHASES) {
                        permits.acquire()
                        acquired++
                    }
                    Lease(acquired, exclusiveCheckpoint = true).also { acquired = 0; transferPhase = false }
                } finally {
                    repeat(acquired) { permits.release() }
                    if (transferPhase) leaveCheckpointPhase()
                }
            }
        } finally {
            synchronized(exportMonitor) {
                checkpointWaiters--
                signalExportChange()
            }
        }
    }

    /** Synchronous offline writers keep nonwaiting admission; live producers use [checkpoint]. */
    public fun tryCheckpoint(): Lease? {
        if (!tryEnterCheckpointPhase()) return null
        var acquired = 0
        var transferPhase = true
        try {
            repeat(QUERY_PHASES) {
                if (!permits.tryAcquire()) return null
                acquired++
            }
            return Lease(acquired, exclusiveCheckpoint = true).also { acquired = 0; transferPhase = false }
        } finally {
            repeat(acquired) { permits.release() }
            if (transferPhase) leaveCheckpointPhase()
        }
    }

    /** Export conversion and delivery share a gate with checkpoints, not the two HTTP query permits. */
    public fun tryExportWork(): Lease? = synchronized(exportMonitor) {
        if (checkpointWaiters > 0 || checkpointActive) {
            null
        } else {
            activeExports++
            Lease(0, activeExport = true)
        }
    }

    /** Bounded final export work waits for a checkpoint and all current exporters to release ownership. */
    public fun awaitExportWork(timeoutMs: Long): Lease? {
        require(timeoutMs >= 0L)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(exportMonitor) {
            while (checkpointPhaseBlocked()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return null
                TimeUnit.NANOSECONDS.timedWait(exportMonitor, remaining)
            }
            activeExports++
            return Lease(0, activeExport = true)
        }
    }

    private suspend fun enterCheckpointPhase() {
        while (true) {
            val waiting = synchronized(exportMonitor) {
                if (activeExports == 0 && !checkpointActive) {
                    checkpointActive = true
                    signalExportChange()
                    null
                } else {
                    exportChanged
                }
            }
            if (waiting == null) return
            waiting.await()
        }
    }

    private fun tryEnterCheckpointPhase(): Boolean = synchronized(exportMonitor) {
        if (checkpointPhaseBlocked()) {
            false
        } else {
            checkpointActive = true
            true
        }
    }

    private fun checkpointPhaseBlocked(): Boolean =
        checkpointWaiters > 0 || checkpointActive || activeExports > 0

    private fun leaveCheckpointPhase() = synchronized(exportMonitor) {
        checkpointActive = false
        signalExportChange()
    }

    private fun leaveExportWork() = synchronized(exportMonitor) {
        check(activeExports > 0) { "No export work owns a phase" }
        activeExports--
        signalExportChange()
    }

    private fun signalExportChange() {
        val previous = exportChanged
        exportChanged = CompletableDeferred()
        previous.complete(Unit)
        exportMonitor.notifyAll()
    }

    /** A transferable permit: enqueue rejection and completion may release it on different threads. */
    public class Lease internal constructor(
        private var owned: Int,
        private var activeExport: Boolean = false,
        private var exclusiveCheckpoint: Boolean = false,
    ) : AutoCloseable {
        /** Relinquishes the caller's handle before passing sole ownership to accepted asynchronous work. */
        @Synchronized
        public fun transferCheckpoint(): Lease {
            check(owned == QUERY_PHASES && exclusiveCheckpoint) { "Only an exclusive checkpoint phase can transfer" }
            return Lease(owned, exclusiveCheckpoint = true).also {
                owned = 0
                exclusiveCheckpoint = false
            }
        }

        @Synchronized
        override fun close() {
            repeat(owned) { permits.release() }
            owned = 0
            if (activeExport) {
                activeExport = false
                leaveExportWork()
            }
            if (exclusiveCheckpoint) {
                exclusiveCheckpoint = false
                leaveCheckpointPhase()
            }
        }
    }
}

// Preserve two concurrent HTTP source reads; checkpoint encoding needs both slots, not a smaller memory charge.
private const val QUERY_PHASES = 2
