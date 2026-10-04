// NEW: collect-only downstream delivery owns the final perf publication and first successful client flush.
package splice.head.turn.delivery

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import splice.core.perf.TurnPerf
import splice.core.util.JsonWire
import splice.head.turn.TurnDrive
import splice.head.turn.TurnTelemetry

/** A collect turn holds its ending, not its snapshot, until downstream delivery has settled. */
internal class CollectPerf {
    private class Publication(
        private val telemetry: TurnTelemetry,
        private val outcome: String,
        private val rateLimited: Boolean,
        private val cause: String?,
        private val layers: Int,
    ) {
        fun publish(drive: TurnDrive) = telemetry.recordSnapshot(drive, outcome, rateLimited, cause, layers)
    }

    private val lock = Any()
    private var deferred = false
    private var published = false
    private var pending: Publication? = null

    fun defer() {
        synchronized(lock) { deferred = true }
    }

    /** Streaming remains immediate. A deferred ending is claimed once, including cancellation cleanup. */
    fun hold(telemetry: TurnTelemetry, outcome: String, rateLimited: Boolean, cause: String?, layers: Int): Boolean =
        synchronized(lock) {
            if (!deferred) {
                false
            } else {
                if (!published && pending == null) pending = Publication(telemetry, outcome, rateLimited, cause, layers)
                true
            }
        }

    /** Called in collect's finally after the engine's suspending write returns or fails. */
    fun publish(drive: TurnDrive) {
        val publication = synchronized(lock) {
            published = true
            pending.also { pending = null }
        }
        publication?.publish(drive)
    }
}

/** Ktor awaits WriteChannelContent.writeTo; there is no detached writer racing perf publication. */
internal class CollectedReply(
    private val body: String,
    override val status: HttpStatusCode,
    private val perf: TurnPerf,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)
    override val contentLength: Long = JsonWire.byteSize(body)

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val output = if (body.isNotEmpty()) FirstByteChannel(channel, perf) else channel
        output.writeStringUtf8(body)
        output.flush()
    }

    /** Encoding can flush before writeStringUtf8 returns; a later failure cannot erase delivered bytes. */
    private class FirstByteChannel(
        private val output: ByteWriteChannel,
        private val perf: TurnPerf,
    ) : ByteWriteChannel by output {
        override suspend fun flush() {
            output.flush()
            perf.firstClientByte()
        }
    }
}
