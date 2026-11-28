// NEW: gateway-no-heads. What the gateway has already decided about a turn when it hands it to a ProviderRuntime: it
// read the body to route (and set `model` to the provider's own id) and admitted the caller at its own door.
// A Ktor call's body can be read once, so the turn travels with it; the call
// itself still carries every header (anthropic-beta, session and agent ids) the pipeline reads as it does for a head.
package splice.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import splice.core.model.ClientWindow
import splice.head.hold.ClientWallHeader
import splice.head.turn.ClientPace
import splice.head.wire.NATIVE_CLIENT_PING_MS

/** One turn as the gateway hands it to a [ProviderRuntime]: [body] is the client's body with `model` already set to
 *  the provider's own id, [door] is what the perf row's `door` says (`desktop`, `claude` or `network`), and
 *  [clientWindow] is a hint from the gateway's launch configuration, not the divisor of any one request.
 *
 *  [listener] is what an authenticated listener in front of the gateway (the team server) says of a turn it relays. It is
 *  typed values the gateway fills, never read from the call's headers: a header on a gateway call is the caller's, and a
 *  caller must not name a ledger row it does not own, pick the upstream or set how often it is pinged. A plain door turn
 *  says nothing.
 *
 *  [withheldHeaders] are the lowercase names of the caller's headers the gateway found carrying one of its own keys: a
 *  provider's `forward_headers` may name them, and they must never ride upstream with a key in them.
 *
 *  [asked] is the model Claude Code named, which [body] no longer says once it asks for the provider's own id; the live
 *  route's turn names it (reply-stream). Null where the gateway was not told.
 *
 *  [requestWindow] is the window the gateway read off THIS request — Claude Desktop's app mode, where the door has
 *  already weighed the request's beta and model against the windows splice.toml's `[desktop]` states. It outranks
 *  what the request's beta alone would say (ClientWindowWitness), which is the client's own million, not this
 *  install's. A bound describes the largest window the gateway's client might use, not a stated divisor;
 *  null means the gateway did not interpret the request's window. */
public class GatewayTurn(
    public val body: ByteArray,
    public val door: String,
    public val clientWindow: Long?,
    public val listener: ListenerSays = ListenerSays(),
    public val withheldHeaders: Set<String> = emptySet(),
    public val asked: String? = null,
    public val requestWindow: ClientWindow? = null,
) {
    /** [body] as a bounded read of it, the same answer a head's own read of a call's body gives: the body, or the cap
     *  it exceeded. */
    internal fun received(limit: Int): BodyRead =
        if (body.size > limit) {
            BodyRead.TooLarge(limit)
        } else {
            BodyRead.Received(ReceivedBody(body.decodeToString(), body.size))
        }
}

/** What the authenticated listener in front of the gateway says of a turn: the server its person prefers, the id it named
 *  the turn by, the notice it sends in place of the turn, [keepaliveMs], how often it asks a streamed turn's client
 *  be sent a keepalive while the answer is silent (null or zero asks for none beyond the native disconnect probe), and
 *  [streamIdleMs], how long the answer may fall silent once the client has seen some of it before the turn's round is
 *  ended whatever its path says (the server's `stream_idle_seconds`, #791; null or zero: no such deadline). */
public data class ListenerSays(
    val preferredUpstream: String? = null,
    val servedTurn: String? = null,
    val notice: String? = null,
    val keepaliveMs: Long? = null,
    val streamIdleMs: Long? = null,
)

/** A gateway turn rides on its call, so every reader of the pipeline that takes the call sees it and a head's own
 *  entry, which attaches none, is read exactly as it always was. Only [ProviderRuntime] attaches one. */
internal object GatewayTurns {
    private val key = AttributeKey<GatewayTurn>("splice.gateway-turn")

    /** The gateway's door is the held-login door for its turns: it proved the caller before it called here, as
     *  HeldLoginDoor proves a head's, so admission need not refuse a turn on a provider holding logins as "not
     *  launched" (LocalRefusal.refuseIfUnproven), which no door of a head ever saw. */
    fun attach(call: ApplicationCall, turn: GatewayTurn) {
        call.attributes.put(key, turn)
        HeldLoginVerdict.prove(call)
    }

    fun of(call: ApplicationCall): GatewayTurn? = call.attributes.getOrNull(key)

    /** How often [call]'s streamed client is pinged: the native disconnect probe, or the shorter keepalive its listener
     *  asked for. */
    fun clientPingMs(call: ApplicationCall): Long =
        minOf(NATIVE_CLIENT_PING_MS, of(call)?.listener?.keepaliveMs?.takeIf { it > 0L } ?: NATIVE_CLIENT_PING_MS)

    /** The deadline [call]'s listener set on a silence once its client has seen content; null where it set none. */
    fun streamDeadlineMs(call: ApplicationCall): Long? = of(call)?.listener?.streamIdleMs?.takeIf { it > 0L }

    /** Admission-time client pacing and hold opt-out read off [call]. */
    fun clientPace(call: ApplicationCall): ClientPace =
        ClientPace(clientPingMs(call), streamDeadlineMs(call), ClientWallHeader.noHold(call))
}

/** Where a request's body comes from: the gateway's, when it handed one over, else the call's own bounded read. The
 *  size limit holds for both, since the gateway is not the one that guards this provider's memory. */
internal object GatewayBody {
    suspend fun read(call: ApplicationCall, limit: Int, reader: RequestBodyReader): BodyRead {
        val turn = GatewayTurns.of(call) ?: return reader.receiveBodyBounded(call, limit)
        return turn.received(limit)
    }

    /** The length the body's heap lease is sized to: the gateway's body, which is the one served (its `model` was
     *  rewritten, so it is not the length the call arrived with), else the call's declared Content-Length. */
    fun declaredLength(call: ApplicationCall): Long? =
        GatewayTurns.of(call)?.body?.size?.toLong()
            ?: call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
}
