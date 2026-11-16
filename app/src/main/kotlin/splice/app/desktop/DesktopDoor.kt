// NEW: desktop-door (docs/specs/desktop-door.md, decisions 1, 2, 5 and 7). A turn of the Code tab's Claude Code,
// answered under /desktop on the control port, which a restart does not move.
//
// The model a request names picks the head. A selector, the ID a head's own /v1/models lists, runs that model on that
// head, and the session is on that head from then. Anthropic's own IDs are a tier (AnthropicTiers): they run the model
// the session's head names for that tier, or its pinned model. Any other model is refused here, in Anthropic's shape,
// and nothing is sent upstream. The turn then goes to its head through the same relay LAN share uses, with the head's
// own turn key in place of the desktop key, so the head's admission, logins, cooldowns and honest failures all apply
// unchanged, and the head is told the turn came through this door, which its row says.
//
// The window (decision 5): the Code tab's Claude Code holds one window for the whole session, the one `on` declared.
// The head already scales a session's counts to the window that session says it holds (ClientWindows), so the door
// says it for each session it relays; a `count_tokens` answer, which a head counts raw, is scaled here by the same
// factor.
package splice.app.desktop

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.app.control.MgmtRoute
import splice.client.models.AnthropicTiers
import splice.core.client.SESSION_HEADER
import splice.core.turn.DESKTOP_DOOR
import splice.core.turn.TURN_DOOR_HEADER
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.LruSizing
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import splice.share.HeadTurn
import splice.share.RefusalVoice
import splice.share.ShareRelay
import splice.share.WholeAnswer
import splice.share.WholeEdit
import java.time.Instant

/** Where a door turn runs: its head, and the ID that head runs it as. */
internal data class DoorRoute(val head: DoorHead, val upstream: String)

/** What a door turn asks: its body, the model it names and the session it is a turn of. */
internal data class DoorAsk(val request: JsonObject, val model: String, val session: String?)

/** Each session's head: the head of the last selector it named, for the tiers it asks for later. The session heard
 *  from longest ago goes first. */
private class SessionHeads {
    private val heads = object : LinkedHashMap<String, String>(INITIAL, LruSizing.LOAD_FACTOR, ORDER) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > SESSIONS_KEPT
    }

    fun put(session: String, head: String) = synchronized(heads) { heads[session] = head }

    fun get(session: String): String? = synchronized(heads) { heads[session] }
}

internal class DesktopDoor(
    private val relay: ShareRelay,
    private val heads: DesktopHeads,
    private val records: DesktopRecords,
    private val key: DesktopKey,
    private val log: LogSink,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val voice = RefusalVoice(DESKTOP_ERROR_PREFIX)
    private val tiers = AnthropicTiers()
    private val json = Json { ignoreUnknownKeys = true }

    // Session -> the head of the last selector it named. Bounded: sessions come and go for the daemon's whole life.
    private val sessions = SessionHeads()

    /** `POST /desktop/v1/messages`, with or without `?beta=true`. */
    suspend fun messages(call: ApplicationCall) = answer(call, counting = false)

    /** `POST /desktop/v1/messages/count_tokens`, with or without `?beta=true`. */
    suspend fun countTokens(call: ApplicationCall) = answer(call, counting = true)

    /** [route] run when [call] presents the desktop key, which opens this door and no other; refused 401 in
     *  Anthropic's shape, with nothing sent upstream, when it presents none, a wrong one or one that was replaced. */
    suspend fun guarded(call: ApplicationCall, route: MgmtRoute) {
        if (key.matchesBearer(call.request.headers[HttpHeaders.Authorization])) return route()
        voice.refuse(call, HttpStatus.UNAUTHORIZED, NO_KEY)
    }

    private suspend fun answer(call: ApplicationCall, counting: Boolean) {
        val lineup = heads.lineup()
        val request = read(call, lineup) ?: return
        val ask = DoorAsk(request, JsonScalars.str(request, MODEL).orEmpty(), call.request.headers[SESSION_HEADER])
        val route = route(lineup, ask.model, ask.session)
            ?: return voice.refuse(call, HttpStatus.BAD_REQUEST, "model \"${ask.model}\" $NOT_IN_DOOR")
        send(call, route, ask, counting)
    }

    /** [call]'s body as a JSON object, or null once [call] has been answered: with no head in the door, a body past
     *  what the widest head takes, or a body that is not JSON. */
    private suspend fun read(call: ApplicationCall, lineup: DesktopLineup): JsonObject? {
        val widest = lineup.heads.maxByOrNull { it.relay.maxRequestBytes() }
            ?: return null.also { voice.refuse(call, HttpStatus.BAD_REQUEST, NO_HEAD) }
        val body = relay.sent(call, widest.relay) ?: return null
        return parsed(body) ?: null.also { voice.refuse(call, HttpStatus.BAD_REQUEST, NOT_JSON) }
    }

    /** [ask] relayed to [route]'s head as the model it runs there, and a count scaled to the declared window. */
    private suspend fun send(call: ApplicationCall, route: DoorRoute, ask: DoorAsk, counting: Boolean) {
        val rewritten = JsonWire.bytes(JsonObject(ask.request + (MODEL to JsonPrimitive(route.upstream))))
        val window = records.get().window
        if (!counting) {
            if (window != null) route.head.windows?.record(ask.session, window)
            val at = Instant.ofEpochMilli(clock()).toString()
            remember(DesktopLastTurn(at, ask.session, route.head.key, ask.model, route.upstream))
        }
        val query = call.request.queryString().let { if (it.isEmpty()) "" else "?$it" }
        val turn = HeadTurn(route.head.relay, (if (counting) COUNT_PATH else MESSAGES_PATH) + query, named = DOOR_NAMED)
        val factor = window?.takeIf { counting }?.let { route.head.catalog.usageScale(route.upstream, it) }
        relay.turn(call, turn, rewritten, factor?.let(::Scaled))
    }

    /** Where [asked] runs for [session], or null when the door does not serve it. A selector runs on its head, and
     *  the session is on that head from then. */
    private fun route(lineup: DesktopLineup, asked: String, session: String?): DoorRoute? {
        val (head, model) = lineup.find(asked) ?: return tiered(lineup, asked, session)
        if (session != null) sessions.put(session, head.key)
        return DoorRoute(head, model.upstream)
    }

    /** An Anthropic ID's tier, run on [session]'s head, else the default model's, as that head runs the tier. */
    private fun tiered(lineup: DesktopLineup, asked: String, session: String?): DoorRoute? {
        val tier = tiers.tierOf(asked) ?: return null
        val held = session?.let(sessions::get)
        val head = lineup.heads.firstOrNull { it.key == held } ?: defaultHead(lineup) ?: return null
        return DoorRoute(head, head.tiers[tier] ?: head.pinned)
    }

    /** The head of the default model, or the first head in the door when that model is no longer in it. */
    private fun defaultHead(lineup: DesktopLineup): DoorHead? =
        records.get().defaultModel?.let { lineup.find(it)?.first } ?: lineup.heads.firstOrNull()

    private fun parsed(body: ByteArray): JsonObject? = try {
        json.parseToJsonElement(body.decodeToString()) as? JsonObject
    } catch (_: SerializationException) {
        null
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
            val answer = parsed(body)
            val counted = JsonScalars.long(answer, INPUT_TOKENS)?.takeIf { factor != 1.0 }
            if (answer == null || counted == null) return WholeAnswer.Given(body)
            val scaled = JsonPrimitive((counted * factor).toLong())
            return WholeAnswer.Given(JsonWire.bytes(JsonObject(answer + (INPUT_TOKENS to scaled))))
        }
    }
}

/** What every refusal the door answers with starts with, so a person reading it in the Code tab knows where to look. */
internal const val DESKTOP_ERROR_PREFIX = "splice desktop: "

private const val NO_KEY = "no desktop key, or one that was replaced; `splice desktop on` writes the current one"
private const val NO_HEAD = "no head is in the door; `splice desktop` says why"
private const val NOT_JSON = "the request body is not a JSON object"
private const val NOT_IN_DOOR = "is not in the door; `splice desktop` lists the models it serves"

private const val MODEL = "model"
private const val INPUT_TOKENS = "input_tokens"
private const val MESSAGES_PATH = "/v1/messages"
private const val COUNT_PATH = "/v1/messages/count_tokens"

// What the door tells a head about each turn it relays: that it came through this door.
private val DOOR_NAMED = mapOf(TURN_DOOR_HEADER to DESKTOP_DOOR)

// why: a few thousand Code tab sessions is more than a person opens between two daemon restarts.
private const val SESSIONS_KEPT = 4_096

// The JDK's own starting size: the map is bounded by its eviction rule, not its capacity.
private const val INITIAL = LruSizing.INITIAL_CAPACITY

// A map in access order, so the session heard from longest ago is the eldest.
private const val ORDER = true
