package splice.upstream.memory

import splice.core.memory.HeapLease
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapRelease
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface FragmentReleased {
    operator fun invoke()
}

/** Cache eviction releases only its handle. Escaped immutable output roots keep both bounds charged.
 *  Cleaner actions retain ticket metadata, never message nodes or a builder/provider.
 */
internal class MessageFragmentCharge(
    private val lease: HeapLease,
    private val released: FragmentReleased,
) {
    private val lock = Any()
    private var owners = 1

    fun <T : Any> pin(owner: T) {
        val pin = lease.share(HeapOwnerState.ACTIVE)
        synchronized(lock) { owners++ }
        val _ = MessageFragmentCleaner.cleaner.register(
            owner,
            Runnable {
                pin.close(HeapRelease.COLLECTED)
                release()
            },
        )
    }

    fun reader(): FragmentReader {
        val reader = lease.share(HeapOwnerState.ACTIVE)
        synchronized(lock) { owners++ }
        return FragmentReader(reader) { release() }
    }

    fun evict() {
        lease.close(HeapRelease.EVICTED)
        release()
    }

    private fun release() {
        val finished = synchronized(lock) {
            owners--
            owners == 0
        }
        if (finished) released()
    }
}

internal class FragmentReader(
    private val lease: HeapLease,
    private val released: FragmentReleased,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            lease.close()
            released()
        }
    }
}

private object MessageFragmentCleaner {
    val cleaner: Cleaner = Cleaner.create()
}
