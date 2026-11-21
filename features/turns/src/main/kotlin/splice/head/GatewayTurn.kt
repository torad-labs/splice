// NEW: gateway-no-heads. What the gateway has already decided about a turn when it hands it to a ProviderRuntime: it
// read the body to route (and set `model` to the provider's own id), admitted the caller at its own door, and knows the
// window this session's Claude Code holds. A Ktor call's body can be read once, so the turn travels with it; the call
// itself still carries every header (anthropic-beta, session and agent ids) the pipeline reads as it does for a head.
package splice.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/** One turn as the gateway hands it to a [ProviderRuntime]: [body] is the client's body with `model` already set to
 *  the provider's own id, [door] is what the perf row's `door` says (`desktop`, `claude` or `network`), and
 *  [clientWindow] is the window this session's client holds, which counts are scaled to; null when the gateway does
 *  not know it, which leaves the window to what the request and the session's own posts say. */
public class GatewayTurn(
    public val body: ByteArray,
    public val door: String,
    public val clientWindow: Long?,
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
