package splice.core.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/** Keeps history encoding and source replies from simultaneously reserving their large workspaces. */
public object PerfWorkPhase {
    private val permits = Semaphore(QUERY_PHASES)
    private val checkpoints = Mutex()

    /** Hosts await inside their existing request deadline and retain ownership through reply release. */
    public suspend fun query(): Lease {
        permits.acquire()
        return Lease(1)
    }

    /** Await before allocating a snapshot or submitting work; never call from the shared file-I/O lane. */
    public suspend fun checkpoint(): Lease = checkpoints.withLock {
        var acquired = 0
        try {
            repeat(QUERY_PHASES) {
                permits.acquire()
                acquired++
            }
            Lease(acquired).also { acquired = 0 }
        } finally {
            repeat(acquired) { permits.release() }
        }
    }

    /** Synchronous offline writers keep nonwaiting admission; live producers use [checkpoint]. */
    public fun tryCheckpoint(): Lease? {
        var acquired = 0
        try {
            repeat(QUERY_PHASES) {
                if (!permits.tryAcquire()) return null
                acquired++
            }
            return Lease(acquired).also { acquired = 0 }
        } finally {
            repeat(acquired) { permits.release() }
        }
    }

    /** A transferable permit: enqueue rejection and completion may release it on different threads. */
    public class Lease internal constructor(private var owned: Int) : AutoCloseable {
        /** Relinquishes the caller's handle before passing sole ownership to accepted asynchronous work. */
        @Synchronized
        public fun transferCheckpoint(): Lease {
            check(owned == QUERY_PHASES) { "Only an exclusive checkpoint phase can transfer" }
            return Lease(owned).also { owned = 0 }
        }

        @Synchronized
        override fun close() {
            repeat(owned) { permits.release() }
            owned = 0
        }
    }
}

// Preserve two concurrent HTTP source reads; checkpoint encoding needs both slots, not a smaller memory charge.
private const val QUERY_PHASES = 2
