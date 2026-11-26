// NEW: desktop-door (docs/specs/desktop-door.md, decisions 5 and 7); gateway-no-heads. A door turn on its way to its
// provider.
//
// The turn is handed to the provider's own serving (ServedProvider), with the body the door read to route it, as the
// provider runs the model, the name of the door it came through, which its row says, and the window the caller's
// sessions hold: the provider's admission, logins, cooldowns and honest failures all apply, and nothing is relayed to a
// head's server. The caller's own key stays at the door and goes no further.
//
// The window (decision 5): a caller's Claude Code holds one window for the whole session, the one its door declared.
// The provider scales a turn's counts, and a `count_tokens` answer, to the window the turn names.
package splice.app.desktop

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import splice.app.desktop.record.DesktopLastTurn
import splice.core.client.AGENT_HEADER
import splice.core.parse.lenientJson
import splice.core.speed.SessionSpeeds
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import splice.head.GatewayTurn
import java.time.Instant

internal class DoorRelay(
    private val clock: WallClock,
    private val sessionSpeeds: SessionSpeeds? = null,
) {
    /** [ask] served for [caller] on [route]'s provider as the model it runs there, or counted, to the window the
     *  caller's sessions hold. */
    suspend fun send(call: ApplicationCall, caller: DoorCaller, route: DoorRoute, ask: DoorAsk, counting: Boolean) {
        // reply-stream: the body asks for the provider's own id, so the turn carries the model Claude Code named.
        val turn = GatewayTurn(
            ask.asking(route.upstream),
            caller.named,
            caller.window(),
            asked = ask.model,
            requestWindow = caller.requestWindowOf(ask),
        )
        if (counting) return route.provider.countTokens(call, turn)
        val at = Instant.ofEpochMilli(clock()).toString()
        caller.sending(DesktopLastTurn(at, ask.session, route.provider.key, ask.model, route.upstream))
        if (!ask.probe && call.request.headers[AGENT_HEADER].isNullOrBlank()) {
            val session = bodySessionId(ask.request) ?: ask.session
            sessionSpeeds?.ran(session, route.upstream, provider = route.provider.booksKey)
        }
        route.provider.serve(call, turn)
    }

    private fun bodySessionId(raw: JsonObject): String? {
        val text = JsonScalars.str((raw["metadata"] as? JsonObject)?.get("user_id")) ?: return null
        val ids = JsonScalars.objectOrNull(lenientJson, text)
        return JsonScalars.str(ids, "session_id")?.takeIf(String::isNotBlank)
    }
}
