package splice.app.control.perf

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import splice.core.perf.runtime.PERF_PROFILE_READ_BUFFER_BYTES
import splice.core.perf.runtime.PerfProfileRead

/** The actual body writer owns the reader until delivery exits, even if the engine responds asynchronously. */
internal class PerfProfileContent(
    private val reader: PerfProfileRead,
    private val io: CoroutineDispatcher,
) : OutgoingContent.WriteChannelContent(), AutoCloseable {
    private val delivered = CompletableDeferred<Unit>()
    private var writer: Job? = null
    private var closed = false
    override val contentType: ContentType = ContentType.parse(reader.artifact.mediaType)
    override val contentLength: Long = reader.artifact.sizeBytes

    override suspend fun writeTo(channel: ByteWriteChannel) {
        begin(currentCoroutineContext().job)
        try {
            reader.use { withTimeout(DELIVERY_MS) { copy(channel) } }
        } finally {
            try {
                channel.flushAndClose()
            } finally {
                synchronized(this) { closed = true; writer = null }
                delivered.complete(Unit)
            }
        }
    }

    suspend fun awaitDelivered() { delivered.await() }

    @Synchronized
    private fun begin(job: Job) {
        check(!closed) { "Profile response is closed" }
        check(writer == null) { "Profile response already has a writer" }
        writer = job
    }

    private suspend fun copy(channel: ByteWriteChannel) {
        val buffer = ByteArray(PERF_PROFILE_READ_BUFFER_BYTES)
        var read = runInterruptible(io) { reader.read(buffer) }
        while (read >= 0) {
            if (read > 0) channel.writeFully(buffer, 0, read)
            read = runInterruptible(io) { reader.read(buffer) }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val activeWriter = writer
        if (activeWriter == null) reader.close() else activeWriter.cancel()
    }
}

// Cancel a profile download after fifteen seconds so a slow client cannot pin its artifact and reader indefinitely.
private const val DELIVERY_MS = 15_000L
