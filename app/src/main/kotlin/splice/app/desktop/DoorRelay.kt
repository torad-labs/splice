// NEW: desktop-door (docs/specs/desktop-door.md, decisions 5 and 7). A door turn on its way to its head.
//
// The turn goes to its head through the same relay LAN share uses, with the head's own turn key in place of the
// desktop key, so the head's admission, logins, cooldowns and honest failures all apply unchanged, and the head is told
// the turn came through this door, which its row says.
//
// The window (decision 5): the Code tab's Claude Code holds one window for the whole session, the one `on` declared.
// The head already scales a session's counts to the window that session says it holds (ClientWindows), so the door
// says it for each session it relays; a `count_tokens` answer, which a head counts raw, is scaled here by the same
// factor.
package splice.app.desktop

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.DESKTOP_DOOR
import splice.core.turn.TURN_DOOR_HEADER
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.share.HeadTurn
import splice.share.ShareRelay
import splice.share.WholeAnswer
import splice.share.WholeEdit
import java.time.Instant

internal class DoorRelay(
    private val relay: ShareRelay,
    private val records: DesktopRecords,
    private val log: LogSink,
    private val clock: WallClock,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** [ask] relayed to [route]'s head as the model it runs there, and a count scaled to the declared window. */
    suspend fun send(call: ApplicationCall, route: DoorRoute, ask: DoorAsk, counting: Boolean) {
        val window = records.get().window
        if (!counting) {
            if (window != null) route.head.windows?.record(ask.session, window)
            val at = Instant.ofEpochMilli(clock()).toString()
            remember(DesktopLastTurn(at, ask.session, route.head.key, ask.model, route.upstream))
        }
        val query = call.request.queryString().let { if (it.isEmpty()) "" else "?$it" }
        val turn = HeadTurn(route.head.relay, (if (counting) COUNT_PATH else MESSAGES_PATH) + query, named = DOOR_NAMED)
        val factor = window?.takeIf { counting }?.let { route.head.catalog.usageScale(route.upstream, it) }
        relay.turn(call, turn, ask.asking(route.upstream), factor?.let(::Scaled))
    }

    /** The last door turn, kept for `splice desktop`. A record the disk refuses is said in the log, and the turn goes
     *  on: what it is for is the answer, not the record of it. */
    private fun remember(turn: DesktopLastTurn) {
        Cancellables.runCatchingCancellable { records.update { it.copy(lastTurn = turn) } }.onFailure { failure ->
            log("[desktop] the last door turn was not recorded: ${SafeFailureText.render(failure)}\n")
        }
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

// What the door tells a head about each turn it relays: that it came through this door.
private val DOOR_NAMED = mapOf(TURN_DOOR_HEADER to DESKTOP_DOOR)
