package splice.observability.profile

import splice.core.perf.runtime.PERF_PROFILE_READ_BUFFER_BYTES
import splice.core.perf.runtime.PerfProfileArtifact
import splice.core.perf.runtime.PerfProfileRead
import splice.core.util.Cancellables
import splice.core.util.PerfWorkBudget
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/** The open descriptor, bounded copy buffer reservation and retention pin have one lifetime. */
internal class ProfileRead(
    override val artifact: PerfProfileArtifact,
    private val channel: FileChannel,
    private val memory: PerfWorkBudget.Lease,
    private val release: AutoCloseable,
) : PerfProfileRead {
    private val closed = AtomicBoolean()

    override fun read(buffer: ByteArray): Int {
        require(buffer.size in 1..PERF_PROFILE_READ_BUFFER_BYTES)
        check(!closed.get()) { "Profile reader is closed" }
        return channel.read(ByteBuffer.wrap(buffer))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Cancellables.withCleanup({ release.close() }) {
            Cancellables.withCleanup({ memory.close() }) { channel.close() }
        }
    }
}
