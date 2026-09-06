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
    ): WsRound?

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

/**
 * One accepted round: its events, and the one operation the head needs on it besides collecting.
 *
 * [abort] tears down THIS round's event source so a stalled round can be reaped without taking the
 * turn down with it (DR-7). It must make [events] fail with an **IOException**, not cancel a
 * coroutine: a torn read is something every stream translator already folds into an honest
 * terminal, so the round's partial survives and the fold loop can continue from it, whereas
 * cancelling the collector takes the translator down with the round and the salvage dies with it.
 * The SSE path reaps exactly this way, by cancelling the response body.
 *
 * WHY THE ABORT RIDES THE ROUND instead of being a method keyed by [TurnMeta], which is what this
 * seam was first widened to: the head has no name for a round that is precise enough. The chaining
 * identity is (session, conversation), but a CONNECTION is identified by that plus the model and a
 * digest of the per-turn headers — deliberately, since a compact turn needs a socket opened without
 * the lite marker — so one conversation can legitimately hold two live rounds on two sockets, and a
 * registry keyed by chain would abort the wrong one (grok-splice, before it shipped). The head
 * cannot recompute the connection key either; the headers are not on the meta. Closing over the
 * round removes the identity question rather than answering it, which is also exactly what the SSE
 * path does with its response body.
 *
 * It may fire LATE, after the round has ended, because the signal and the round's own completion
 * race by construction. An implementation must make that a no-op — see the lease check in the
 * Responses runner for why "has this round finished" is not the same question as "is this
 * connection idle".
 */
public data class WsRound(
    public val events: Flow<JsonObject>,
    public val abort: WsRoundAbort,
)

/** [WsRound.abort]'s one operation, as an interface rather than a function type (kt-no-lambda-seam). */
public fun interface WsRoundAbort {
    public fun abort()
}

/** Thrown by the head when a WS round failed BEFORE the client saw any content, so the round is
 *  re-served over SSE. A plain RuntimeException on purpose: the stream translators' catch lists
 *  (IOException / SerializationException / IllegalArgumentException) must not swallow it, the same
 *  reason [StreamTornBeforeClient] is one. [detail] is what the server said — the failure terminal's
 *  type and error — so the fallback line names WHY a round left the WebSocket: until 2026-09-05 it
 *  said only that one did, and every chained compaction that failed this way (and then re-read the
 *  whole transcript cold over SSE) left no cause anywhere. */
public class WsRoundNeedsSse(public val detail: String = "") :
    RuntimeException("websocket round failed before any client frame")
