// The door on the network (docs/specs/door-network.md). The one listener splice opens beyond loopback, and only when
// `[share] enabled`: the wall kt-embedded-server-loopback exempts this file alone, by that decision. Another machine's
// Claude Code is pointed at its door the way Claude Code is pointed at Vertex or Bedrock, and holds no credential: this
// machine signs every turn with its own logins. Every request passes LanAdmission first (a source on this machine's own
// networks, naming this machine in its Host, and no Origin), and then:
//
//   GET  /                                  this machine's name, with an empty list of commands (ShareInfo), which a
//                                           0.6.4 node's `splice remote` reads to say its door remote is ready
//   GET  /door/launch, POST /door/v1/...    the door (ShareDoor): every model this machine serves, picked by the
//                                           model a request names
//   ANY  /<anything else>/...               404 saying what replaced it: since share-door-only (Nov 19, 2026) this
//                                           machine shares its door alone, never one command at an address of its
//                                           own, and the add for the door names the Host the request used
//
// Claude Code sends no Origin and posts its turns as application/json. A web page in a browser on the LAN sends an
// Origin with every POST, and without a preflight (which nothing here approves) can post only a "simple" body, never
// JSON. So a request with an Origin is refused, and so is a turn whose body is not JSON: a page can't run a turn on
// this machine's logins by addressing it by IP or as localhost, which the Host check admits. Every error is in the
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
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import splice.core.topology.ShareConfig
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.http.listen.HttpListenerThreads

public class ShareListener(
    private val share: ShareConfig,
    /** This machine's name, as its other machines are told it. */
    private val machine: String,
    /** The door, answered under `/door`. */
    private val door: ShareDoor,
    /** Blocking work runs here: reading this machine's networks. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
) {
    private val admission = LanAdmission(machine, share.hostnames, HostPrefixes(log))
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
        log("[share] offering this machine's door on :${share.port} to its LAN and tailnet\n")
    }

    public fun stop() {
        engine?.stop(SHARE_STOP_GRACE_MS, SHARE_STOP_TIMEOUT_MS)
        engine = null
    }

    private suspend fun answer(call: ApplicationCall) {
        val refusal = withContext(io) { admissionRefusal(call) }
        if (refusal != null) return ShareErrors.refuse(call, HttpStatus.FORBIDDEN, refusal)
        val parts = call.request.path().split('/').filter(String::isNotEmpty)
        when (parts.firstOrNull()) {
            null -> named(call)
            DOOR_SEGMENT -> atDoor(call, parts.drop(1))
            else -> ShareErrors.refuse(call, HttpStatus.NOT_FOUND, replaced(call))
        }
    }

    /** `GET /`: this machine's name, with no command of its own. */
    private suspend fun named(call: ApplicationCall) {
        val body = json.encodeToString(ShareInfo.serializer(), ShareInfo(machine, emptyList()))
        call.respondText(body, ContentType.Application.Json)
    }

    /** What an address naming one command is told: the door replaced it, and the add to run for it on the other
     *  machine, at the Host [call] used, as sent: admission has already vouched for it. */
    private fun replaced(call: ApplicationCall): String {
        val host = call.request.headers[HttpHeaders.Host].orEmpty()
        return "$machine no longer shares one command at an address of its own: its door serves every model it has. " +
            "On the other machine, run: splice remote add http://$host"
    }

    /** [rest], the path after `/door`: the launch another machine's `claude` reads, or a turn or a count. */
    private suspend fun atDoor(call: ApplicationCall, rest: List<String>) {
        val posted = call.request.httpMethod == HttpMethod.Post
        val turn = rest == doorMessages || rest == doorCount
        when {
            !posted && rest == listOf(HEAD_LAUNCH_ROUTE) -> doorLaunch(call)
            posted && turn -> doorTurn(call, counting = rest == doorCount)
            else -> ShareErrors.refuse(call, HttpStatus.NOT_FOUND, "no such path on $machine's door")
        }
    }

    /** A turn, or a count when [counting], posted to the door: its body must be JSON, as every turn's here must. */
    private suspend fun doorTurn(call: ApplicationCall, counting: Boolean) {
        if (!sentAsJson(call)) {
            return ShareErrors.refuse(call, HttpStatus.BAD_REQUEST, "a turn's body must be application/json")
        }
        door.answer(call, counting)
    }

    /** `GET /door/launch`: what another machine's `claude` launches with; 503 while the heads are still starting. */
    private suspend fun doorLaunch(call: ApplicationCall) {
        val launch = door.launch()
            ?: return ShareErrors.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, "$machine's door $DOOR_STARTING")
        call.respondText(json.encodeToString(SharedDoorLaunch.serializer(), launch), ContentType.Application.Json)
    }

    /** Why this request may not be answered at all, or null when it may: LanAdmission's two checks, and no Origin,
     *  which a browser puts on every request a page posts and Claude Code puts on none. */
    private fun admissionRefusal(call: ApplicationCall): String? {
        val host = call.request.headers[HttpHeaders.Host]
        return admission.refusal(call.request.origin.remoteAddress)
            ?: "this Host is not one of this machine's names".takeUnless { admission.admitsHost(host) }
            ?: "a web page may not reach this machine's door".takeIf { HttpHeaders.Origin in call.request.headers }
    }

    /** Whether [call] says its body is application/json, in any case and with any parameters. Read from the header as
     *  sent: Ktor's parser throws on a value that names no media type, such as "json", and its exception (a plain
     *  Exception) answered that turn with a 500 in plain text instead of this refusal (PR #247 review r2). */
    private fun sentAsJson(call: ApplicationCall): Boolean =
        call.request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
            .equals(ContentType.Application.Json.toString(), ignoreCase = true)
}

// All interfaces: LanAdmission decides who is answered, by source address and Host, before anything else runs. On a
// host with IPv6 the JDK binds this wildcard as `::`, so IPv6 sources on the LAN reach it too.
private const val SHARE_BIND_HOST = "0.0.0.0"

// The paths after `/door` that a turn and a count are posted to, as Claude Code's Mantle dialect posts them.
private const val DOOR_MESSAGES_SEGMENT = "messages"
private const val DOOR_COUNT_SEGMENT = "count_tokens"

// why: a stop lets requests already in the listener finish for a second before it closes; a door turn still streaming
// is cut, and the other machine's Claude Code retries it as it retries any cut turn.
private const val SHARE_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a turn stuck on a head cannot hold up a restart.
private const val SHARE_STOP_TIMEOUT_MS = 5_000L
