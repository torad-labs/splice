// NEW: 2026-09-23, the head's SSE response body (moved 2026-10-09 from splice.head.wire to the HTTP integration, so the
// control plane's two streams, events and MCP, end on the same clean close) — Ktor's own
// WriterContent, plus the one guarantee
// Ktor's does not give: a frame the body wrote and flushed reaches the client even when the body
// then ends by throwing.
//
// WHY Ktor's alone loses it (read in the 3.5.2 sources, not recalled). The Netty engine hands the
// body a plain ByteChannel (NettyApplicationResponse.responseChannel) and runs it inside
// `responseChannel().use { content.writeTo(this) }` (BaseApplicationResponse.
// respondWriteChannelContent). A body that throws reaches `use`'s catch, which calls close(cause),
// and for a non-null cause that is cancel(cause) (ByteWriteChannelOperations.close). Once cancelled,
// the channel's readBuffer throws that cause BEFORE it moves the flushed bytes across
// (ByteChannel.readBuffer), so every byte the engine's reader has not yet taken is dropped — and
// flush() only moved them into the channel (ByteChannel.flush returns while there is room), it never
// waited for the reader. Under load the reader lags, and what it drops is exactly the frame the
// cancellation seal wrote last: HeadServerCompactCapTest's compact arm in gate-aee915e4 (head log
// frames_out=3 bytes_out=568, client body 396+35 bytes, the 137-byte wall error frame missing) and
// HeadServerFoldTest NF-03 on CI run 35838271235 (bytes_out=137, client bodyLen=0).
//
// THE FIX is one finally: flushAndClose() sets a CLEAN close before the throw reaches `use`, so the
// later cancel(cause) is a no-op (ByteChannel.cancel returns on any existing close) and the reader
// drains everything that was written, then sees end of stream. The throw itself still propagates:
// a cancelled turn stays cancelled.
//
// NOT wrapped in NonCancellable, deliberately. flushAndClose's only suspension is its flush, inside
// its own runCatching, and flushWriteBuffer has already moved the bytes before that point
// (ByteChannel.flushAndClose); a cancelled Job's throw there is caught and the close is still set.
// NonCancellable would add exactly one thing — waiting for a reader that may never drain again — so a
// cancelled turn on a client that stopped reading would hang here instead of ending. SseResponseTest
// pins both the full-channel, cancelled-Job path and the lagging-reader path.
package splice.http

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.WriterContent
import io.ktor.http.withCharset
import io.ktor.utils.io.ByteWriteChannel
import java.io.Writer

/** What one SSE response writes: every frame, through the blocking [Writer] the response opens. */
public fun interface SseBody {
    public suspend operator fun invoke(out: Writer)
}

/**
 * An SSE body written through a blocking [Writer], exactly as `respondTextWriter` writes it, whose
 * flushed frames are delivered however the body ends.
 *
 * The only way the daemon answers with a stream (the kt-head-sse-drains-on-exit wall keeps Ktor's raw
 * writers out of every module): TurnStreamer's driven turn and LocalResponses' local answer and
 * compaction replay all end on a frame written last, and a stream whose last frame can vanish is the
 * "empty or malformed response (HTTP 200)" class the cancellation seal exists to prevent.
 */
public class SseResponse(body: SseBody) : OutgoingContent.WriteChannelContent() {

    // `text/event-stream; charset=UTF-8`, the value respondTextWriter's defaultTextContentType gave.
    private val writer = WriterContent({ body(this) }, ContentType.Text.EventStream.withCharset(Charsets.UTF_8))

    override val contentType: ContentType = writer.contentType

    override suspend fun writeTo(channel: ByteWriteChannel) {
        try {
            writer.writeTo(channel)
        } finally {
            channel.flushAndClose()
        }
    }
}

/** What one control-plane stream writes: every frame, flushed, to the response's channel. */
public fun interface SseChannelBody {
    public suspend operator fun invoke(channel: ByteWriteChannel)
}

/**
 * An SSE body written straight to the response channel, for the streams that frame their own writes (events, MCP),
 * closed CLEAN in a finally for the same reason [SseResponse] is: a body that ends by throwing must not cancel the
 * channel over frames the engine's reader has not taken yet.
 */
public class SseChannelResponse(private val body: SseChannelBody) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Text.EventStream

    override suspend fun writeTo(channel: ByteWriteChannel) {
        try {
            body(channel)
        } finally {
            channel.flushAndClose()
        }
    }
}
