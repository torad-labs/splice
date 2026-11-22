// NEW: desktop-door (docs/specs/desktop-door.md, decisions 1, 2, 5 and 7). A turn of Claude Code in Mantle mode,
// answered under /door on the control port, which a restart does not move (and under /desktop, its path before the
// door had two callers, for a Desktop set up then), and under /door on the share listener for another machine
// (door-network).
//
// door-default (docs/specs/door-default.md, decision 3): the door admits two callers, Claude Desktop's Code tab and the
// terminal's `claude`, each by its own key (DoorCaller), and no other key opens it. While a caller's switch is off the
// door refuses its turns, saying how to open it, and nothing is sent upstream. The model a request names picks the head
// (DoorRouting); a model the door does not serve is refused here, in Anthropic's shape, and nothing is sent upstream. A
// turn with a route goes to its head as the model that head runs it as (DoorRelay). For the terminal's `claude`, a
// model of Anthropic's that a request names runs where a head serves it, or is refused by name (DoorRouting).
package splice.app.desktop

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.DoorCatalog
import splice.app.desktop.catalog.ModelDiscovery
import splice.client.desktop.ANTHROPIC_BETA_HEADER
import splice.client.desktop.APP_MODE_HEADER
import splice.client.desktop.DesktopAppWindow
import splice.client.desktop.GatewayDiscovery
import splice.core.client.SESSION_HEADER
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.http.JsonReply
import splice.provider.antigravity.DoorAgyGuard
import splice.share.CappedBodies
import splice.share.RefusalVoice

/** What a door turn asks: its body, the model it names, the session it is a turn of, and, for a request of Claude
 *  Desktop's app mode, the window Desktop gave its Code tab for that model ([appWindow], null for any other request). */
internal data class DoorAsk(
    val request: JsonObject,
    val model: String,
    val session: String?,
    val appWindow: Long? = null,
) {
    /** Whether this is Claude Code's start-up check: one token, not streamed (desktop-door.md's table), whose answer
     *  no one reads. */
    val probe: Boolean
        get() = JsonScalars.long(request, REQUEST_MAX_TOKENS) == 1L &&
            JsonScalars.str(request, REQUEST_STREAM) != "true"

    /** The body as a head takes it: the same request, asking for [upstream], the ID that head runs the model as. */
    fun asking(upstream: String): ByteArray =
        JsonWire.bytes(JsonObject(request + (REQUEST_MODEL to JsonPrimitive(upstream))))
}

internal class DesktopDoor(
    private val callers: List<DoorCaller>,
    private val providers: DesktopProviders,
    clock: WallClock = WallClock(System::currentTimeMillis),
) {
    /** The door's own voice, for a key it holds for no caller: it can't tell whose session that is, so its words name
     *  splice alone and say where each caller's key comes from. */
    private val voice = RefusalVoice("")
    private val json = Json { ignoreUnknownKeys = true }
    private val routing = DoorRouting()
    private val toProvider = DoorRelay(clock)
    private val windows = DesktopAppWindow()
    private val discovery = GatewayDiscovery()
    private val gatewayModels = GatewayModels()

    /** `POST /door/v1/messages`, with or without `?beta=true`. */
    suspend fun messages(call: ApplicationCall) = answer(call, counting = false)

    /** `POST /door/v1/messages/count_tokens`, with or without `?beta=true`. */
    suspend fun countTokens(call: ApplicationCall) = answer(call, counting = true)

    /** `GET /door/v1/models` (`limit`, `after_id`): the door's models as its caller reads them. Claude Desktop's
     *  gateway discovery takes the catalog's Desktop list (DoorCatalog); the terminal's `claude`, in Claude Code's
     *  gateway mode, takes the Splice gateway's one list (head-catalog, GatewayModels), read at each request. */
    suspend fun models(call: ApplicationCall) {
        val caller = admitted(call) ?: return
        val lineup = providers.lineup()
        val catalog = DoorCatalog(lineup)
        val default = caller.default(lineup)
        if (caller is DesktopCaller) {
            caller.discovered(catalog.desktopRead(default))
            JsonReply(HttpStatusCode.OK, catalog.discovery(default, ModelDiscovery.DESKTOP).toString()).send(call)
            return
        }
        val query = call.request.queryParameters
        val page = discovery.page(
            gatewayModels.of(lineup),
            query[LIMIT_PARAM]?.toIntOrNull(),
            query[AFTER_ID],
        )
        JsonReply(HttpStatusCode.OK, page.toString()).send(call)
    }

    /** [call] answered for the caller whose key it presents; refused in Anthropic's shape, with nothing sent upstream,
     *  when it presents none, a wrong one or one that was replaced. */
    private suspend fun answer(call: ApplicationCall, counting: Boolean) {
        val caller = admitted(call) ?: return
        routed(call, caller, counting)
    }

    /** [call], which something other than a key admitted (the share listener, by where the machine is: door-network),
     *  answered for the caller whose key it presents all the same, so Desktop's Code tab is Desktop at either
     *  listener, and for [caller] when it presents none the door knows. Refused as that caller's door refuses while it
     *  is shut. */
    suspend fun answerAs(call: ApplicationCall, caller: DoorCaller, counting: Boolean) {
        val from = keyed(call) ?: caller
        val shut = from.closed()
        if (shut != null) return from.voice.refuse(call, DOOR_REFUSAL_STATUS, shut)
        routed(call, from, counting)
    }

    /** The caller whose key [call] presents; null when it presents none the door knows. A gateway client sends its
     *  key as `x-api-key` unless told bearer, as Claude Desktop's own process does (desktop-full, df-wire-door).
     *  Authorization decides when both are there. */
    private fun keyed(call: ApplicationCall): DoorCaller? {
        val headers = call.request.headers
        val presented = headers[HttpHeaders.Authorization] ?: headers[DOOR_API_KEY_HEADER]?.let { "Bearer $it" }
        return callers.firstOrNull { it.presents(presented) }
    }

    /** [call], from [caller], read and sent to the provider the model it names picks. */
    private suspend fun routed(call: ApplicationCall, caller: DoorCaller, counting: Boolean) {
        val lineup = providers.lineup()
        val request = read(call, caller, lineup) ?: return
        val model = JsonScalars.str(request, REQUEST_MODEL).orEmpty()
        val headers = call.request.headers
        val window = windows.of(headers[APP_MODE_HEADER], headers[ANTHROPIC_BETA_HEADER], model)
        val ask = DoorAsk(request, model, headers[SESSION_HEADER], window)
        caller.asked(ask)
        val route = routing.of(lineup, ask, caller.default(lineup), caller.runsNamedModels)
            ?: desktopOnlyRoute(call, caller, lineup, ask)
            ?: return
        toProvider.send(call, caller, route, ask, counting)
    }

    /** Routes a `desktopOnly` (Antigravity) model for Claude Desktop Chat turns, while refusing Claude Code CLI
     *  agent loops and non-Desktop callers with a clear explanation (desktop-antigravity.md). */
    private suspend fun desktopOnlyRoute(
        call: ApplicationCall,
        caller: DoorCaller,
        lineup: DesktopLineup,
        ask: DoorAsk,
    ): DoorRoute? {
        val desktopHit = lineup.findDesktopOnly(ask.model)
        if (desktopHit == null) {
            caller.voice.refuse(call, HttpStatus.BAD_REQUEST, "model \"${ask.model}\" ${caller.notInDoor}")
            return null
        }
        val headers = call.request.headers
        val isCli = DoorAgyGuard.isClaudeCodeRequest(headers[HttpHeaders.UserAgent], headers[X_APP_HEADER], ask.request)
        if (caller !is DesktopCaller || isCli) {
            caller.voice.refuse(call, HttpStatus.BAD_REQUEST, "model \"${ask.model}\" $DESKTOP_CHAT_ONLY")
            return null
        }
        return DoorRoute(desktopHit.first, desktopHit.second.upstream)
    }

    /** The caller whose key [call] presents, while its switch is on; null once [call] has been refused
     *  ([DOOR_REFUSAL_STATUS]): with no key it knows, or saying how to open a caller's door that is shut. */
    private suspend fun admitted(call: ApplicationCall): DoorCaller? {
        val caller = keyed(call)
        if (caller == null) {
            voice.refuse(call, DOOR_REFUSAL_STATUS, NO_DOOR_KEY)
            return null
        }
        val shut = caller.closed() ?: return caller
        caller.voice.refuse(call, DOOR_REFUSAL_STATUS, shut)
        return null
    }

    /** [call]'s body as a JSON object, or null once [call] has been answered: with no provider in the door, a body past
     *  what the widest provider takes, or a body that is not JSON. */
    private suspend fun read(call: ApplicationCall, caller: DoorCaller, lineup: DesktopLineup): JsonObject? {
        val candidates = if (caller is DesktopCaller) lineup.providers + lineup.desktopOnly else lineup.providers
        val widest = candidates.maxByOrNull { it.maxRequestBytes }
            ?: return null.also { caller.voice.refuse(call, HttpStatus.BAD_REQUEST, caller.noHead) }
        val body = CappedBodies(caller.voice).read(call, widest.maxRequestBytes, widest.requestReadTimeoutMs)
            ?: return null
        return JsonScalars.objectOrNull(json, body.decodeToString())
            ?: null.also { caller.voice.refuse(call, HttpStatus.BAD_REQUEST, NOT_JSON) }
    }
}

/** What every refusal the door answers Desktop with starts with, so a person reading it in the Code tab knows where to
 *  look. */
internal const val DESKTOP_ERROR_PREFIX = "splice desktop: "

/** The status the door refuses a key it doesn't hold, or a shut door, with. Claude Code reads a 401 or a 403 from a
 *  Mantle endpoint as AWS's and puts "AWS credentials expired or invalid" or "AWS authentication failed" before
 *  splice's words (seen on 2.1.328), which sent people to AWS for a key splice holds. That branch reads only those two
 *  statuses, so a 400 shows splice's words without it. Until Nov 18, 2026 the door answered 401 and 403. */
private const val DOOR_REFUSAL_STATUS = HttpStatus.BAD_REQUEST

private const val NO_DOOR_KEY =
    "Splice credentials expired or invalid · this session's key is not one the door holds: a new `claude` session " +
        "carries the terminal's, and `splice desktop on` gives Claude Desktop its own"

// The header a gateway client may present its key in, in place of Authorization.
private const val DOOR_API_KEY_HEADER = "x-api-key"

private const val NOT_JSON = "the request body is not a JSON object"

internal const val REQUEST_MODEL: String = "model"
private const val REQUEST_MAX_TOKENS = "max_tokens"
private const val REQUEST_STREAM = "stream"

// The query a `/v1/models` page is asked with (Claude Code's gateway discovery).
private const val LIMIT_PARAM = "limit"
private const val AFTER_ID = "after_id"
private const val X_APP_HEADER = "x-app"
private const val DESKTOP_CHAT_ONLY =
    "runs only in Claude Desktop (Chat or Code tab), not through Claude Code's HTTP loop"
