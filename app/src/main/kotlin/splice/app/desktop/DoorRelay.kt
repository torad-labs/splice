// NEW: desktop-door (docs/specs/desktop-door.md, decisions 5 and 7). A door turn on its way to its head.
//
// The turn goes to its head through the same relay LAN share uses, with the head's own turn key in place of the
// caller's door key, so the head's admission, logins, cooldowns and honest failures all apply unchanged, and the head
// is told the turn came through this door and from which caller, which its row says.
//
// The window (decision 5): a caller's Claude Code holds one window for the whole session, the one its door declared.
// The head already scales a session's counts to the window that session says it holds (ClientWindows), so the door
// says it for each session it relays; a `count_tokens` answer, which a head counts raw, is scaled here by the same
// factor.
package splice.app.desktop

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.TURN_DOOR_HEADER
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.WallClock
import splice.share.HeadTurn
import splice.share.WholeAnswer
import splice.share.WholeEdit
import java.time.Instant

internal class DoorRelay(private val clock: WallClock) {
    private val json = Json { ignoreUnknownKeys = true }

    /** [ask] relayed for [caller] to [route]'s head as the model it runs there, and a count scaled to the window the
     *  caller's sessions hold. */
    suspend fun send(call: ApplicationCall, caller: DoorCaller, route: DoorRoute, ask: DoorAsk, counting: Boolean) {
        val window = caller.window()
        if (!counting) {
            if (window != null) route.head.windows?.record(ask.session, window)
            val at = Instant.ofEpochMilli(clock()).toString()
            caller.sending(DesktopLastTurn(at, ask.session, route.head.key, ask.model, route.upstream))
        }
        val query = call.request.queryString().let { if (it.isEmpty()) "" else "?$it" }
        val path = (if (counting) COUNT_PATH else MESSAGES_PATH) + query
        val turn = HeadTurn(route.head.relay, path, named = mapOf(TURN_DOOR_HEADER to caller.named))
        val factor = window?.takeIf { counting }?.let { route.head.catalog.usageScale(route.upstream, it) }
        caller.relay.turn(call, turn, ask.asking(route.upstream), factor?.let(::Scaled))
    }

    /** A `count_tokens` answer as the same share of the declared window that its count is of the model's own. An
     *  answer with no count is given as it came. */
    private inner class Scaled(private val factor: Double) : WholeEdit {
        override fun read(body: ByteArray): WholeAnswer {
            val answer = JsonScalars.objectOrNull(json, body.decodeToString())
            val counted = JsonScalars.long(answer, INPUT_TOKENS)?.takeIf { factor != 1.0 }
            if (answer == null || counted == null) return WholeAnswer.Given(body)
            val scaled = JsonPrimitive((counted * factor).toLong())
            return WholeAnswer.Given(JsonWire.bytes(JsonObject(answer + (INPUT_TOKENS to scaled))))
        }
    }
}

private const val INPUT_TOKENS = "input_tokens"
private const val MESSAGES_PATH = "/v1/messages"
private const val COUNT_PATH = "/v1/messages/count_tokens"
