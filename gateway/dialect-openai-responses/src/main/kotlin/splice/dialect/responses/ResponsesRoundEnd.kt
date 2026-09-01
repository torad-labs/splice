// NEW: round-terminal vocabulary and the handshake-header seam. Split from
// ResponsesWsRunner.kt so the runner is not billed for the two small types
// (concentration, 2026-08-19).
package splice.dialect.responses

import splice.core.auth.Credentials

/** Round-terminal vocabulary, mirrored from ResponsesStreamTranslator.kt. Both sets end a
 *  round: the flow must complete on EITHER, or the connection never returns to the pool and the
 *  round hangs — strictly worse than an error. Failure terminals additionally let the head bail to
 *  SSE while the client has still seen nothing. */
internal object ResponsesRoundEnd {
    val SUCCESS = setOf("response.completed", "response.done", "response.incomplete")
    val FAILED = setOf("response.failed", "response.error", "error")
    val ALL = SUCCESS + FAILED
}

/**
 * The headers that open the WebSocket, derived from the credentials in hand at connect time —
 * `Authorization` plus the `OpenAI-Beta: responses_websockets=…` opt-in the v2 backend gates on.
 *
 * Read ONCE per connection and not per round, which is what separates it from
 * [splice.spi.CredentialHeaders] despite the shape: those are rebuilt per ATTEMPT so a post-401
 * refresh reaches the wire, while a WebSocket's handshake cannot be re-run without dropping the
 * connection and the per-connection server context that is the whole point of reusing it.
 * Non-suspending for the same reason — there is no refresh to await here.
 */
internal fun interface HandshakeHeaders {
    operator fun invoke(credentials: Credentials): Map<String, String>
}
