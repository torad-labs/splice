// NEW: LAN share (docs/specs/lan-share.md). The one listener splice opens beyond
// loopback, and only when `[share] enabled`: the wall kt-embedded-server-loopback exempts this file alone, by that
// decision. Another machine's Claude Code is pointed here the way Claude Code is pointed at Vertex or Bedrock, and holds
// no credential: this machine signs every turn with its own logins. Every request passes LanAdmission first (a source
// on this machine's own networks, naming this machine in its Host), and then:
//
//   GET  /                                  what this machine offers: each command, its head, models and logins
//   GET  /<command>/launch?auth=&models=     what the other machine's launch needs (SharedLaunch), filtered by both
//   POST /<command>[/@<login>]/statusline   the control plane's statusline for that head, with the turn key;
//                                           `@<login>` first pins the session the body names, so the line is that
//                                           login's from the first draw
//   ANY  /<command>[/@<login>]/v1/...       relayed to the head on loopback with the turn key in place of what was
//                                           sent; `@<login>` first pins the session to that login, as Accounts' Pin
//   GET  /door/launch, POST /door/v1/...    the door (door-network, ShareDoor): every model this machine serves,
//                                           picked by the model a request names
//
// Claude Code sends no Origin and posts its turns as application/json. A web page in a browser on the LAN sends an
// Origin with every POST, and without a preflight (which nothing here approves) can post only a "simple" body, never
// JSON. So a request with an Origin is refused, and so is a turn whose body is not JSON: a page can't run a turn on
// this machine's logins by addressing it by IP or as localhost, which the Host check admits.
//
// A command may be named by the command it runs as here or by its head's key. One not offered is 404 naming the ones
// that are; one offered but not running, or not answering, is 503 in this machine's name. Every error is in the
// Anthropic shape, so Claude Code prints its message, and none of them says "overloaded".
package splice.share

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import splice.core.client.SESSION_HEADER
import splice.core.config.TurnKey
import splice.core.topology.ShareConfig
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.http.listen.HttpListenerThreads

public class ShareListener(
    private val share: ShareConfig,
    private val heads: SharedHeads,
    turnKey: TurnKey,
    /** The control plane's port on loopback, whose statusline a shared command's statusline is. */
    controlPort: Int,
    /** Blocking work runs here: reading this machine's networks, a head's logins, and the relay's JDK I/O. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    /** The door, answered under `/door`; null where this daemon runs none. */
    private val door: ShareDoor? = null,
) {
    private val admission = LanAdmission(heads.machine, share.hostnames, HostPrefixes(log))
    private val relay = ShareRelay(turnKey, controlPort, heads.machine, io, log)
    private val catalog = ShareCatalog(heads, io, ShareErrors.voice)
    private val launches = SharedLaunches(heads.machine)
    private val json = Json { encodeDefaults = true }
    private val doorMessages = listOf(HEAD_API_ROOT, DOOR_MESSAGES_SEGMENT)
    private val doorCount = doorMessages + DOOR_COUNT_SEGMENT

    @Volatile
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    public fun start() {
        val server = embeddedServer(
            Netty,
            serverConfig { module { intercept(ApplicationCallPipeline.Call) { answer(call) } } },
        ) {
            HttpListenerThreads.configure(this)
            connector {
                host = SHARE_BIND_HOST
                port = share.port
            }
            shutdownGracePeriod = SHARE_STOP_GRACE_MS
        }
        server.start(wait = false)
        engine = server
        log("[share] offering this machine's heads on :${share.port} to its LAN\n")
    }

    public fun stop() {
        engine?.stop(SHARE_STOP_GRACE_MS, SHARE_STOP_TIMEOUT_MS)
        engine = null
    }

    private suspend fun answer(call: ApplicationCall) {
        val refusal = withContext(io) { admissionRefusal(call) }
        if (refusal != null) return ShareErrors.refuse(call, HttpStatus.FORBIDDEN, refusal)
        val parts = call.request.path().split('/').filter(String::isNotEmpty)
        val named = parts.firstOrNull() ?: return catalog.directory(call)
        if (named == DOOR_SEGMENT) return atDoor(call, parts.drop(1))
        val head = catalog.named(call, named) ?: return
        route(call, head, parts.drop(1))
    }

    /** [rest], the path after [head]'s name: a turn, with the login it names first if any, its launch, or its
     *  statusline. */
    private suspend fun route(call: ApplicationCall, head: SharedHead, rest: List<String>) {
        val login = rest.firstOrNull()?.takeIf { it.startsWith(LOGIN_MARK) }?.drop(1)
        val path = if (login != null) rest.drop(1) else rest
        when {
            path.firstOrNull() == HEAD_API_ROOT -> turn(call, head, login, path)
            login == null && path == listOf(HEAD_LAUNCH_ROUTE) -> catalog.launch(call, head)
            path == listOf(STATUSLINE) -> relay.statusline(call, head) { body -> login?.let { lined(head, it, body) } }
            else -> ShareErrors.refuse(call, HttpStatus.NOT_FOUND, "no such path on ${heads.machine}")
        }
    }

    /** [rest], the path after `/door`: the launch another machine's `claude` reads, or a turn or a count, whose body
     *  must be JSON as every turn's here must. */
    private suspend fun atDoor(call: ApplicationCall, rest: List<String>) {
        val door = door ?: return ShareErrors.refuse(call, HttpStatus.NOT_FOUND, "${heads.machine} runs no door")
        val posted = call.request.httpMethod == HttpMethod.Post
        val turn = rest == doorMessages || rest == doorCount
        when {
            !posted && rest == listOf(HEAD_LAUNCH_ROUTE) -> doorLaunch(call, door)
            posted && turn && !sentAsJson(call) ->
                ShareErrors.refuse(call, HttpStatus.BAD_REQUEST, "a turn's body must be application/json")
            posted && turn -> door.answer(call, counting = rest == doorCount)
            else -> ShareErrors.refuse(call, HttpStatus.NOT_FOUND, "no such path on ${heads.machine}'s door")
        }
    }

    /** `GET /door/launch`: what another machine's `claude` launches with; 503 while the heads are still starting. */
    private suspend fun doorLaunch(call: ApplicationCall, door: ShareDoor) {
        val launch = door.launch()
        if (launch == null) {
            val why = "${heads.machine}'s door opens once its heads are up"
            return ShareErrors.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, why)
        }
        call.respondText(json.encodeToString(SharedDoorLaunch.serializer(), launch), ContentType.Application.Json)
    }

    /** Why this request may not be answered at all, or null when it may: LanAdmission's two checks, and no Origin,
     *  which a browser puts on every request a page posts and Claude Code puts on none. */
    private fun admissionRefusal(call: ApplicationCall): String? {
        val host = call.request.headers[HttpHeaders.Host]
        return admission.refusal(call.request.origin.remoteAddress)
            ?: "this Host is not one of this machine's names".takeUnless { admission.admitsHost(host) }
            ?: "a web page may not reach this machine's heads".takeIf { HttpHeaders.Origin in call.request.headers }
    }

    /** A turn for [head], on [login] when the address named one. A login it lacks is refused rather than run on
     *  another; a turn that names no session cannot be pinned, so with a login named it is refused too. */
    private suspend fun turn(call: ApplicationCall, head: SharedHead, login: String?, path: List<String>) {
        if (!head.running()) return catalog.notRunning(call, head)
        val posted = call.request.httpMethod == HttpMethod.Post
        if (posted && !sentAsJson(call)) {
            return ShareErrors.refuse(call, HttpStatus.BAD_REQUEST, "a turn's body must be application/json")
        }
        if (login != null) {
            val session = call.request.headers[SESSION_HEADER]?.takeIf(String::isNotBlank)
            val refusal = withContext(io) { pinned(head, login, session, call.request.httpMethod != HttpMethod.Get) }
            if (refusal != null) return ShareErrors.refuse(call, HttpStatus.BAD_REQUEST, refusal)
        }
        val query = call.request.queryString().takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
        relay.turn(call, HeadTurn(head, path.joinToString("/", prefix = "/") + query))
    }

    /** Whether [call] says its body is application/json, in any case and with any parameters. Read from the header as
     *  sent: Ktor's parser throws on a value that names no media type, such as "json", and its exception (a plain
     *  Exception) answered that turn with a 500 in plain text instead of this refusal (PR #247 review r2). */
    private fun sentAsJson(call: ApplicationCall): Boolean =
        call.request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
            .equals(ContentType.Application.Json.toString(), ignoreCase = true)

    /** Pins [session] to [login] on [head], or says why it can't: null once pinned, or when there is nothing to pin
     *  and the request [runs] no turn. */
    private fun pinned(head: SharedHead, login: String, session: String?, runs: Boolean): String? {
        val logins = head.logins()
        return when {
            login !in logins -> launches.noLogin(head, login, logins)
            session != null && head.pin(login, session) -> null
            session != null -> launches.noLogin(head, login, head.logins())
            // Nothing to pin: a read that runs no turn goes through; a turn would run on another login, so it doesn't.
            !runs -> null
            else ->
                "this request names no Claude Code session, so it can't be run on ${heads.machine}'s login '$login'"
        }
    }

    /** Pins the session a statusline [body] names (Claude Code's `session_id`) to [login] on [head] before the line
     *  is drawn, so it shows that login's usage before the session's first turn; a body naming none is drawn as is. */
    private fun lined(head: SharedHead, login: String, body: ByteArray): String? {
        val session = JsonScalars.str(JsonScalars.objectOrNull(json, body.decodeToString()), STATUSLINE_SESSION)
        return pinned(head, login, session?.takeIf(String::isNotBlank), runs = false)
    }
}

// All interfaces: LanAdmission decides who is answered, by source address and Host, before anything else runs. On a
// host with IPv6 the JDK binds this wildcard as `::`, so IPv6 sources on the LAN reach it too.
private const val SHARE_BIND_HOST = "0.0.0.0"

// The path segment that marks the login a turn runs on (`/claudex/@work/v1/messages`).
private const val LOGIN_MARK = "@"
private const val STATUSLINE = "statusline"

// The paths after `/door` that a turn and a count are posted to, as Claude Code's Mantle dialect posts them.
private const val DOOR_MESSAGES_SEGMENT = "messages"
private const val DOOR_COUNT_SEGMENT = "count_tokens"

// The key Claude Code names a statusline's session under in the JSON it feeds the statusline.
private const val STATUSLINE_SESSION = "session_id"

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and the other machine's Claude Code retries it as it retries any cut turn.
private const val SHARE_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SHARE_STOP_TIMEOUT_MS = 5_000L
