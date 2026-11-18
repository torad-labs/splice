// NEW: desktop-door (docs/specs/desktop-door.md, decisions 1, 2, 5 and 7). A turn of Claude Code in Mantle mode,
// answered under /desktop on the control port, which a restart does not move.
//
// door-default (docs/specs/door-default.md, decision 3): the door admits two callers, Claude Desktop's Code tab and the
// terminal's `claude`, each by its own key (DoorCaller), and no other key opens it. While a caller's switch is off the
// door refuses its turns, saying how to open it, and nothing is sent upstream. The model a request names picks the head
// (DoorRouting); a model the door does not serve is refused here, in Anthropic's shape, and nothing is sent upstream. A
// turn with a route goes to its head as the model that head runs it as (DoorRelay). For the terminal's `claude`, a
// model of Anthropic's that a request names runs where a head serves it, or is refused by name (DoorRouting).
package splice.app.desktop

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.client.SESSION_HEADER
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.share.RefusalVoice

/** What a door turn asks: its body, the model it names and the session it is a turn of. */
internal data class DoorAsk(val request: JsonObject, val model: String, val session: String?) {
    /** Whether this is Claude Code's start-up check: one token, not streamed (desktop-door.md's table), whose answer
     *  no one reads. */
    val probe: Boolean
        get() = JsonScalars.long(request, MAX_TOKENS) == 1L && JsonScalars.str(request, STREAM) != "true"

    /** The body as a head takes it: the same request, asking for [upstream], the ID that head runs the model as. */
    fun asking(upstream: String): ByteArray =
        JsonWire.bytes(JsonObject(request + (REQUEST_MODEL to JsonPrimitive(upstream))))
}

internal class DesktopDoor(
    private val callers: List<DoorCaller>,
    private val heads: DesktopHeads,
    clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val voice = RefusalVoice(DESKTOP_ERROR_PREFIX)
    private val json = Json { ignoreUnknownKeys = true }
    private val routing = DoorRouting()
    private val toHead = DoorRelay(clock)

    /** `POST /desktop/v1/messages`, with or without `?beta=true`. */
    suspend fun messages(call: ApplicationCall) = answer(call, counting = false)

    /** `POST /desktop/v1/messages/count_tokens`, with or without `?beta=true`. */
    suspend fun countTokens(call: ApplicationCall) = answer(call, counting = true)

    /** [call] answered for the caller whose key it presents; refused 401 in Anthropic's shape, with nothing sent
     *  upstream, when it presents none, a wrong one or one that was replaced. */
    private suspend fun answer(call: ApplicationCall, counting: Boolean) {
        val caller = admitted(call) ?: return
        val lineup = heads.lineup()
        val request = read(call, caller, lineup) ?: return
        val model = JsonScalars.str(request, REQUEST_MODEL).orEmpty()
        val ask = DoorAsk(request, model, call.request.headers[SESSION_HEADER])
        val route = routing.of(lineup, ask, caller.default(lineup), caller.runsNamedModels)
            ?: return caller.voice.refuse(call, HttpStatus.BAD_REQUEST, "model \"${ask.model}\" ${caller.notInDoor}")
        toHead.send(call, caller, route, ask, counting)
    }

    /** The caller whose key [call] presents, while its switch is on; null once [call] has been refused: 401 with no
     *  key it knows, 403 saying how to open a caller's door that is shut. */
    private suspend fun admitted(call: ApplicationCall): DoorCaller? {
        val presented = call.request.headers[HttpHeaders.Authorization]
        val caller = callers.firstOrNull { it.presents(presented) }
        if (caller == null) {
            voice.refuse(call, HttpStatus.UNAUTHORIZED, NO_DOOR_KEY)
            return null
        }
        val shut = caller.closed() ?: return caller
        caller.voice.refuse(call, HttpStatus.FORBIDDEN, shut)
        return null
    }

    /** [call]'s body as a JSON object, or null once [call] has been answered: with no head in the door, a body past
     *  what the widest head takes, or a body that is not JSON. */
    private suspend fun read(call: ApplicationCall, caller: DoorCaller, lineup: DesktopLineup): JsonObject? {
        val widest = lineup.heads.maxByOrNull { it.relay.maxRequestBytes() }
            ?: return null.also { caller.voice.refuse(call, HttpStatus.BAD_REQUEST, caller.noHead) }
        val body = caller.relay.sent(call, widest.relay) ?: return null
        return JsonScalars.objectOrNull(json, body.decodeToString())
            ?: null.also { caller.voice.refuse(call, HttpStatus.BAD_REQUEST, NOT_JSON) }
    }
}

/** What every refusal the door answers Desktop with starts with, so a person reading it in the Code tab knows where to
 *  look. */
internal const val DESKTOP_ERROR_PREFIX = "splice desktop: "

private const val NO_DOOR_KEY =
    "no door key, or one that was replaced; `splice desktop on` says Desktop's, and a new `claude` session carries " +
        "the terminal's"
private const val NOT_JSON = "the request body is not a JSON object"

private const val REQUEST_MODEL = "model"
private const val MAX_TOKENS = "max_tokens"
private const val STREAM = "stream"
