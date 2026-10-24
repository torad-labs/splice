// NEW: LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24). The one listener splice opens beyond
// loopback, and only when `[share] enabled`: the wall kt-embedded-server-loopback exempts this file alone, by that
// decision. Another machine's Claude Code is pointed here the way Claude Code is pointed at Vertex or Bedrock, and holds
// no credential: this machine signs every turn with its own logins. Every request passes LanAdmission first (a source
// on this machine's own networks, naming this machine in its Host), and then:
//
//   GET  /                                  what this machine offers: each command, its head, models and logins
//   GET  /<command>/launch?auth=&models=     what the other machine's launch needs (SharedLaunch), filtered by both
//   POST /<command>/statusline              the control plane's statusline for that head, with the turn key
//   ANY  /<command>[/@<login>]/v1/...       relayed to the head on loopback with the turn key in place of what was
//                                           sent; `@<login>` first pins the session to that login, as Accounts' Pin
//
// A command may be named by the command it runs as here or by its head's key. One not offered is 404 naming the ones
// that are; one offered but not running, or not answering, is 503 in this machine's name. Every error is in the
// Anthropic shape, so Claude Code prints its message, and none of them says "overloaded".
package splice.share

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import splice.core.config.TurnKey
import splice.core.topology.ShareConfig
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
) {
    private val admission = LanAdmission(heads.machine, share.hostnames, HostPrefixes(log))
    private val relay = ShareRelay(turnKey, controlPort, heads.machine, io, log)
    private val launches = SharedLaunches(heads.machine)
    private val json = Json { encodeDefaults = true }

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
        val named = parts.firstOrNull() ?: return info(call)
        val offered = heads.offered()
        val head = offered.firstOrNull { it.command == named } ?: offered.firstOrNull { it.key == named }
        if (head == null) {
            val offers = offered.joinToString(", ") { it.command }.ifEmpty { "nothing" }
            val why = "${heads.machine} offers no command named '$named'; it offers $offers"
            return ShareErrors.refuse(call, HttpStatus.NOT_FOUND, why)
        }
        route(call, head, parts.drop(1))
    }

    /** [rest], the path after [head]'s name: a turn, with the login it names first if any, its launch, or its
     *  statusline. */
    private suspend fun route(call: ApplicationCall, head: SharedHead, rest: List<String>) {
        val login = rest.firstOrNull()?.takeIf { it.startsWith(LOGIN_MARK) }?.drop(1)
        val path = if (login != null) rest.drop(1) else rest
        when {
            path.firstOrNull() == API_ROOT -> turn(call, head, login, path)
            login == null && path == listOf(LAUNCH) -> launch(call, head)
            login == null && path == listOf(STATUSLINE) -> relay.statusline(call, head)
            else -> ShareErrors.refuse(call, HttpStatus.NOT_FOUND, "no such path on ${heads.machine}")
        }
    }

    /** Why this request may not be answered at all, or null when it may: LanAdmission's two checks. */
    private fun admissionRefusal(call: ApplicationCall): String? {
        val host = call.request.headers[HttpHeaders.Host]
        return admission.refusal(call.request.origin.remoteAddress)
            ?: "this Host is not one of this machine's names".takeUnless { admission.admitsHost(host) }
    }

    private suspend fun info(call: ApplicationCall) {
        val offers = withContext(io) {
            heads.offered().map { head ->
                val models = head.launch()?.models?.availableModelIds.orEmpty()
                ShareOffer(head.command, head.key, head.running(), models, head.logins())
            }
        }
        val body = json.encodeToString(ShareInfo.serializer(), ShareInfo(heads.machine, offers))
        call.respondText(body, ContentType.Application.Json)
    }

    private suspend fun launch(call: ApplicationCall, head: SharedHead) {
        if (!head.running()) return notRunning(call, head)
        val params = call.request.queryParameters
        val read = withContext(io) { launches.of(head, params[AUTH_PARAM], params[MODELS_PARAM]) }
        when (read) {
            is SharedLaunchRead.Read -> {
                val body = json.encodeToString(SharedLaunch.serializer(), read.launch)
                call.respondText(body, ContentType.Application.Json)
            }
            is SharedLaunchRead.Refused -> ShareErrors.refuse(call, read.status, read.why)
        }
    }

    /** A turn for [head], on [login] when the address named one. A login it lacks is refused rather than run on
     *  another; a turn that names no session cannot be pinned, so with a login named it is refused too. */
    private suspend fun turn(call: ApplicationCall, head: SharedHead, login: String?, path: List<String>) {
        if (!head.running()) return notRunning(call, head)
        if (login != null) {
            val refusal = withContext(io) { pinned(call, head, login) }
            if (refusal != null) return ShareErrors.refuse(call, HttpStatus.BAD_REQUEST, refusal)
        }
        val query = call.request.queryString().takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
        relay.turn(call, head, path.joinToString("/", prefix = "/") + query)
    }

    /** Pins this request's session to [login] on [head], or says why it can't: null once pinned. */
    private fun pinned(call: ApplicationCall, head: SharedHead, login: String): String? {
        val logins = head.logins()
        val session = call.request.headers[SESSION_HEADER]?.takeIf(String::isNotBlank)
        return when {
            login !in logins -> launches.noLogin(head, login, logins)
            session != null && head.pin(login, session) -> null
            session != null -> launches.noLogin(head, login, head.logins())
            // Nothing to pin: a read that runs no turn goes through; a turn would run on another login, so it doesn't.
            call.request.httpMethod == HttpMethod.Get -> null
            else -> "this request names no Claude Code session, so it can't be run on ${heads.machine}'s login " +
                "'$login'"
        }
    }

    private suspend fun notRunning(call: ApplicationCall, head: SharedHead) {
        val why = "${head.command} is not running on ${heads.machine}; `splice restart` there starts it again"
        ShareErrors.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, why)
    }
}

/** Errors in the Anthropic shape, so Claude Code on the other machine prints the message instead of a bare status. The
 *  type follows the status as Anthropic's own does, and is never `overloaded_error`: a head that can't take a turn is
 *  said as that, never as a busy server worth retrying in silence. */
internal object ShareErrors {
    fun body(status: Int, message: String): String {
        val type = when (status) {
            HttpStatus.BAD_REQUEST, HttpStatus.REQUEST_TIMEOUT -> "invalid_request_error"
            HttpStatus.FORBIDDEN -> "permission_error"
            HttpStatus.NOT_FOUND -> "not_found_error"
            HttpStatus.CONTENT_TOO_LARGE -> "request_too_large"
            else -> "api_error"
        }
        val said = Json.encodeToString(String.serializer(), "splice share: $message")
        return """{"type":"error","error":{"type":"$type","message":$said}}"""
    }

    suspend fun refuse(call: ApplicationCall, status: Int, message: String) {
        call.respondText(body(status, message), ContentType.Application.Json, HttpStatusCode.fromValue(status))
    }
}

/** What `GET /` answers: this machine and the commands it offers. */
@Serializable
internal data class ShareInfo(val machine: String, val commands: List<ShareOffer>)

/** One offered command: what an address names it by, its head, whether it runs now, its models and its logins. */
@Serializable
internal data class ShareOffer(
    val command: String,
    val head: String,
    val running: Boolean,
    val models: List<String>,
    val logins: List<String>,
)

// All interfaces: LanAdmission decides who is answered, by source address and Host, before anything else runs. On a
// host with IPv6 the JDK binds this wildcard as `::`, so IPv6 sources on the LAN reach it too.
private const val SHARE_BIND_HOST = "0.0.0.0"

// The path segment that marks the login a turn runs on (`/claudex/@work/v1/messages`), and the API's own root.
private const val LOGIN_MARK = "@"
private const val API_ROOT = "v1"
private const val LAUNCH = "launch"
private const val STATUSLINE = "statusline"
internal const val AUTH_PARAM = "auth"
internal const val MODELS_PARAM = "models"

// Claude Code's own name for the session a turn belongs to, the one a pin is kept under.
private const val SESSION_HEADER = "x-claude-code-session-id"

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and the other machine's Claude Code retries it as it retries any cut turn.
private const val SHARE_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SHARE_STOP_TIMEOUT_MS = 5_000L
