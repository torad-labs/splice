// NEW: desktop-door (docs/specs/desktop-door.md, decisions 1, 2, 5 and 7). A turn of the Code tab's Claude Code,
// answered under /desktop on the control port, which a restart does not move.
//
// The desktop key opens the door and no other key does. The model a request names picks the head (DoorRouting); a
// model the door does not serve is refused here, in Anthropic's shape, and nothing is sent upstream. A turn with a
// route goes to its head as the model that head runs it as (DoorRelay).
package splice.app.desktop

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.control.MgmtRoute
import splice.core.client.SESSION_HEADER
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.share.RefusalVoice
import splice.share.ShareRelay

/** What a door turn asks: its body, the model it names and the session it is a turn of. */
internal data class DoorAsk(val request: JsonObject, val model: String, val session: String?) {
    /** The body as a head takes it: the same request, asking for [upstream], the ID that head runs the model as. */
    fun asking(upstream: String): ByteArray =
        JsonWire.bytes(JsonObject(request + (REQUEST_MODEL to JsonPrimitive(upstream))))
}

internal class DesktopDoor(
    private val relay: ShareRelay,
    private val heads: DesktopHeads,
    records: DesktopRecords,
    private val key: DesktopKey,
    log: LogSink,
    clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val voice = RefusalVoice(DESKTOP_ERROR_PREFIX)
    private val json = Json { ignoreUnknownKeys = true }
    private val routing = DoorRouting(records)
    private val toHead = DoorRelay(relay, records, log, clock)

    /** `POST /desktop/v1/messages`, with or without `?beta=true`. */
    suspend fun messages(call: ApplicationCall) = answer(call, counting = false)

    /** `POST /desktop/v1/messages/count_tokens`, with or without `?beta=true`. */
    suspend fun countTokens(call: ApplicationCall) = answer(call, counting = true)

    /** [route] run when [call] presents the desktop key, which opens this door and no other; refused 401 in
     *  Anthropic's shape, with nothing sent upstream, when it presents none, a wrong one or one that was replaced. */
    suspend fun guarded(call: ApplicationCall, route: MgmtRoute) {
        if (key.matchesBearer(call.request.headers[HttpHeaders.Authorization])) return route()
        voice.refuse(call, HttpStatus.UNAUTHORIZED, NO_DESKTOP_KEY)
    }

    private suspend fun answer(call: ApplicationCall, counting: Boolean) {
        val lineup = heads.lineup()
        val request = read(call, lineup) ?: return
        val model = JsonScalars.str(request, REQUEST_MODEL).orEmpty()
        val ask = DoorAsk(request, model, call.request.headers[SESSION_HEADER])
        val route = routing.of(lineup, ask.model, ask.session)
            ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, "model \"${ask.model}\" $NOT_IN_DOOR")
        toHead.send(call, route, ask, counting)
    }

    /** [call]'s body as a JSON object, or null once [call] has been answered: with no head in the door, a body past
     *  what the widest head takes, or a body that is not JSON. */
    private suspend fun read(call: ApplicationCall, lineup: DesktopLineup): JsonObject? {
        val widest = lineup.heads.maxByOrNull { it.relay.maxRequestBytes() }
            ?: return null.also { voice.refuse(call, HttpStatus.BAD_REQUEST, NO_HEAD) }
        val body = relay.sent(call, widest.relay) ?: return null
        return JsonScalars.objectOrNull(json, body.decodeToString())
            ?: null.also { voice.refuse(call, HttpStatus.BAD_REQUEST, NOT_JSON) }
    }
}

/** What every refusal the door answers with starts with, so a person reading it in the Code tab knows where to look. */
internal const val DESKTOP_ERROR_PREFIX = "splice desktop: "

private const val NO_DESKTOP_KEY =
    "no desktop key, or one that was replaced; `splice desktop on` writes the current one"
private const val NO_HEAD = "no head is in the door; `splice desktop` says why"
private const val NOT_JSON = "the request body is not a JSON object"
private const val NOT_IN_DOOR = "is not in the door; `splice desktop` lists the models it serves"

private const val REQUEST_MODEL = "model"
