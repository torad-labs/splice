// NEW: how an upstream response BODY ended, as a value (kt-no-exception-as-outcome, 2026-10-09).
//
// The handler [UpstreamHandler] runs inside the client's execute block, where the retry loop cannot see what the stream
// did. A tear before the client saw a frame and a frame over our size limit used to leave it as thrown exceptions that
// every translator had to know to rethrow; the handler returns them instead, and the loop branches on them.
package splice.upstream

import splice.upstream.transport.SseFrameTooLarge
import java.io.IOException

/** What the handler made of one response body. */
public sealed class StreamRead<out T> {
    /** The body was consumed and [value] is the handler's answer. */
    public data class Read<T>(public val value: T) : StreamRead<T>()

    /** The connection tore before the client saw a frame this round, so the loop may re-issue the request. */
    public class Torn(public val cause: IOException) : StreamRead<Nothing>()

    /** A frame crossed our own safety limit. No re-issue can help; the turn ends on [ending]. */
    public class Oversized(public val ending: SseFrameTooLarge) : StreamRead<Nothing>()
}
