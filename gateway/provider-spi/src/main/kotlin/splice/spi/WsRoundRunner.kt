// NEW: (ws-transport WS-3, 2026-08-01) the WS seam the generic head drives — and ONLY the seam.
// The concrete transport (WsUpstream/WsConnection) deliberately does NOT live here: it is used by
// exactly one caller, the Responses dialect, and keeping an implementation type in :provider-spi
// widened the shared surface for no consumer (review of #72). :gateway needs the interface and the
// sentinel, nothing more, and the module law gives it nothing more.
package splice.spi

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import splice.core.auth.Credentials
import splice.core.turn.TurnMeta

/**
 * The WS seam the generic head drives (ws-transport WS-3). It lives in :provider-spi because the
 * module law forbids :gateway from naming a dialect type (splice.module-law.gradle.kts:15-31), so
 * every Responses-specific decision — the round-terminal vocabulary, the chaining frame, the
 * pre-content failure test — is supplied by the provider behind this interface.
 *
 * Contract: [attempt] returns null for "serve this round over SSE", which is the answer to every
 * failure (NEVER-BELOW-STATUS-QUO). The head then runs its normal upstream POST, and SSE keeps
 * sole ownership of retry, the single-flight 401 refresh and the shared 429 cooldown (L5).
 */
public interface WsRoundRunner {

    /** Attempt one round; null = ride SSE. [turnHeaders] are the PER-TURN headers the SSE path
     *  would send — they participate in connection identity, because a WebSocket's handshake
     *  headers are fixed for the socket's life and a turn needing a different set must not reuse
     *  a socket opened without it (adversarial review of WS-3: a lite marker was silently dropped). */
    public suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): Flow<JsonObject>?

    /** True when [event] ends the round in FAILURE. The head uses this to bail to SSE while the
     *  client has still seen nothing, so an upstream error keeps SSE's retry/refresh/cooldown
     *  rather than being served raw over the WebSocket. */
    public fun isFailureTerminal(event: JsonObject): Boolean

    /** Called once the round is over: [ok] true only for a clean, fully-consumed terminal.
     *  Anything else must clear the chaining state — a response id that was never completed would
     *  anchor the next turn onto context the server never finished building. */
    public fun roundEnded(meta: TurnMeta, ok: Boolean)

    /** The head served this round WITHOUT the overlay (no credentials, transport declined, or a
     *  pre-content failure fell back). The conversation still advanced, so any chaining state must
     *  be dropped — a chain anchored before a turn the server never saw would make the next delta
     *  omit that turn entirely. */
    public fun roundBypassed(meta: TurnMeta)
}

/** Thrown by the head when a WS round failed BEFORE the client saw any content, so the round is
 *  re-served over SSE. A plain RuntimeException on purpose: the stream translators' catch lists
 *  (IOException / SerializationException / IllegalArgumentException) must not swallow it, the same
 *  reason [StreamTornBeforeClient] is one. */
public class WsRoundNeedsSse : RuntimeException("websocket round failed before any client frame")
