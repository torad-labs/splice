// NEW: LAN share (docs/specs/door-network.md). The one listener splice opens beyond loopback, and only when
// `[share] enabled`: this machine's door on its LAN and tailnet, and nothing else. Another machine runs
// `splice remote add http://<box>` once, and its plain `claude` lists every model this machine serves, on the accounts
// signed in here.
//
//   GET  /                                  what this machine offers: its name, and no command of its own
//   GET  /door/launch, POST /door/v1/...    the door (door-network, ShareDoor): every model this machine serves,
//                                           picked by the model a request names
//
// The per-command share (docs/specs/lan-share.md) is gone. Any other first path segment is a 404 in the Anthropic
// error shape, in words a person can act on: this machine no longer shares one command at an address of its own, its
// door serves every model it has, and the line to run on the other machine. Claude Code prints that message; a node's
// launch prints it before Claude Code starts. Every request passes LanAdmission first (a source on this machine's own
// networks, naming this machine in its Host), so a web page is refused before any of it.
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
import splice.core.topology.DOOR_SEGMENT
import splice.core.topology.ShareConfig
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.http.listen.HttpListenerThreads

public class ShareListener(
    private val share: ShareConfig,
    /** This machine's name, as its other machines are told it. */
    private val machine: String,
    /** Blocking work runs here: reading this machine's networks, which every admission does. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
    /** The door, answered under `/door`; null where this daemon runs none. */
    private val door: ShareDoor? = null,
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
            null -> directory(call)
            DOOR_SEGMENT -> atDoor(call, parts.drop(1))
            else -> ShareErrors.refuse(call, HttpStatus.NOT_FOUND, doorOnly(call))
        }
    }

    /** `GET /`: this machine's name, and the empty list of commands that a 0.6.4 node's `splice remote` reads to tell
     *  a door remote is ready. */
    private suspend fun directory(call: ApplicationCall) {
        val body = json.encodeToString(ShareInfo.serializer(), ShareInfo(machine, emptyList()))
        call.respondText(body, ContentType.Application.Json)
    }

    /** What another machine is told when it asks for one command at an address of its own: this machine shares its
     *  door alone, and the line that puts the other machine's `claude` on it. The add names the Host the request used,
     *  which is the address that reached this machine. */
    private fun doorOnly(call: ApplicationCall): String {
        val host = call.request.headers[HttpHeaders.Host]?.takeIf(String::isNotBlank) ?: "127.0.0.1:${share.port}"
        return "$machine no longer shares one command at an address of its own; its door serves every model it has. " +
            "on another machine, run: splice remote add http://$host"
    }

    /** [rest], the path after `/door`: the launch another machine's `claude` reads, or a turn or a count. */
    private suspend fun atDoor(call: ApplicationCall, rest: List<String>) {
        val door = door ?: return ShareErrors.refuse(call, HttpStatus.NOT_FOUND, "$machine runs no door")
        val posted = call.request.httpMethod == HttpMethod.Post
        val turn = rest == doorMessages || rest == doorCount
        when {
            !posted && rest == listOf(HEAD_LAUNCH_ROUTE) -> doorLaunch(call, door)
            posted && turn -> doorTurn(call, door, counting = rest == doorCount)
            else -> ShareErrors.refuse(call, HttpStatus.NOT_FOUND, "no such path on $machine's door")
        }
    }

    /** A turn, or a count when [counting], posted to the door: its body must be JSON, as every turn's here must. */
    private suspend fun doorTurn(call: ApplicationCall, door: ShareDoor, counting: Boolean) {
        if (!sentAsJson(call)) {
            return ShareErrors.refuse(call, HttpStatus.BAD_REQUEST, "a turn's body must be application/json")
        }
        door.answer(call, counting)
    }

    /** `GET /door/launch`: what another machine's `claude` launches with; 503 while the heads are still starting. */
    private suspend fun doorLaunch(call: ApplicationCall, door: ShareDoor) {
        val launch = door.launch()
        if (launch == null) {
            val why = "$machine's door $DOOR_STARTING"
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

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and the other machine's Claude Code retries it as it retries any cut turn.
private const val SHARE_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SHARE_STOP_TIMEOUT_MS = 5_000L
