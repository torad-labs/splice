// NEW: server mode (docs/specs/server-mode.md, F1). The listener that makes this machine a team's front door: it
// opens only when splice.toml has a `[server]`, on the address that table names, any address included, which is why
// the wall kt-embedded-server-loopback exempts this file, by that spec's decision. LAN share answers anyone on the
// person's own network and holds no identity; this listener answers only keys, each one a person's, from anywhere:
//
//   GET  /health                   open: the listener is up.
//   GET  /server/signin, POST /server/signin/token
//                                  open: how a team member signs in and gets a key (F2, SigninRoute). Nothing else is
//                                  answered without a key.
//   GET  /                         what this server offers the key's holder: each command, its head and models
//   GET  /server/profile           what a laptop builds its commands from: each command, its models and their
//                                  windows, the sign-in configuration, the telemetry relay, the minimum client (F7)
//   GET  /<command>/launch         what a laptop's launch of that command needs (as share mode serves it)
//   ANY  /<command>/v1/...         relayed to the head on loopback with this machine's turn key in place of the
//                                  person's key, and the answer streamed back as it arrives
//   POST /<command>/v1/chat/completions, GET /<command>/v1/models, GET /<command>/v1/models/<id>
//                                  an OpenAI-style client's, for a command whose provider speaks openai-chat: relayed
//                                  to that provider's own server, refusals in OpenAI's error shape (OpenAiRoute)
//   ANY  /server/api/...           the server's own API, for a key whose person is an admin (ServerAdmin)
//   GET  /server/metrics           each person's traffic, live, as Prometheus text, for an admin's key (F6)
//   POST /server/otlp/v1/{logs,metrics,traces}
//                                  a laptop's Claude Code telemetry, stamped with the key's person and team and relayed
//                                  to the collector `[server.telemetry] forward_to` names; no such path without one (F8)
//
// A key arrives as `x-api-key` or as `Authorization: Bearer`. A missing, unknown, revoked or expired one is a 401
// `authentication_error` that says which and never repeats the key, and nothing reaches a head (KeyAdmission). Only
// the commands `[server] commands` lists are served; any other name is 404 naming those. A
// `POST /<command>/v1/messages` is a turn: it counts against its person's in-flight, per-minute and token caps
// (PersonLimits), and one past a cap is a 429 `rate_limit_error` with `Retry-After` (TurnAdmission); one on a command
// its person may not use is a 403 `permission_error` naming it (CommandAccess). Every error is in the Anthropic
// shape, so Claude Code prints its message. A turn from a laptop whose splice is older than `[server.client] minimum`
// is answered 200 by its head, in the conversation, with what to upgrade to, and reaches no upstream and no cap
// (ClientMinimum); `[server.client]` may name another version header, refuse it with 400 or 426 instead, word the
// notice, and take a laptop's version once at `POST /server/client/checkin` (ClientCheckins, #791). A chat completion
// and a door turn meet the same minimum.
//
// `[server.aliases]` (#791) lets a client that cannot change its base path reach a command or the door at a plain
// prefix: with `"/v1" = "claude-team"`, `/v1/chat/completions` is `/claude-team/v1/chat/completions`. The path is
// rewritten once as it is read, before any route judges it (PathAliases).
//
// A person's key is the server's secret on the way out as well. A head sends its provider the caller's headers that
// provider's `forward_headers` names, and one of those whose value holds a key this server issued, the caller's own
// or another person's, is not handed to the head at all: the turn is served without it, and the log names the header
// and never the key (ProviderBound).
//
// A command whose provider is several servers (F4): a turn is handed to its head naming the upstream its person
// prefers, which the head sends it to while that one is healthy; and while none of the provider's servers is up the
// turn is refused here, a 503 naming them, before it takes a slot or reaches the head.
//
// With `[server] keepalive_seconds` a streamed turn is not left silent while its model server prefills a long prompt:
// its stream is opened within two seconds and carries an SSE keepalive at least that often until the answer comes,
// and after it whenever the answer falls silent (HeadAnswers, QuietWire). A turn that asks for no stream answers one
// JSON body as before. An OpenAI-style client's streamed completion is kept alive the same way (OpenAiRoute).
//
// A path is read the way a head reads one, percent-escapes decoded, and a head is handed the path as it was read
// (RequestPaths): what a head serves as a turn is what the caps counted, however the client spelled it. A command's
// own routes, its turns and its launch, are HeadCommandRoute's.
//
// HTTP/2 is off: Claude Code speaks HTTP/1.1, and a turn's client leaving is read off its connection (WatchedRelay),
// which is one request's on HTTP/1.1.
package splice.server

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import splice.core.topology.DOOR_SEGMENT
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.http.listen.HttpListenerThreads
import splice.server.caller.ClientAddresses
import splice.server.caller.KeyAdmission
import splice.server.client.ClientCheckins
import splice.server.client.ClientMinimum
import splice.server.path.PathAliases
import splice.server.path.RequestPaths
import splice.server.signin.SigninParts
import splice.server.signin.SigninRoute
import splice.server.telemetry.OTLP_ROUTE
import splice.server.telemetry.TelemetryRelay
import splice.server.web.ServerPublicRoutes
import splice.share.RefusalVoice
import splice.share.ShareCatalog
import java.security.KeyStore
import kotlin.time.Duration.Companion.seconds

internal class ServerListener(
    private val server: ServerConfig,
    private val parts: ServerParts,
    /** Its TLS identity when it speaks HTTPS itself; null for plain HTTP. */
    private val tls: KeyStore?,
    /** The ledger's reports and the live metrics, which an admin reads over the listener. */
    reads: AdminReads,
    /** Blocking work runs here: reading a head's launch, and the relay's JDK I/O. */
    io: CoroutineDispatcher,
    private val log: LogSink,
    /** How a sign-in is checked, when `[server.signin]` is set (F2). */
    signin: SigninParts? = null,
) {
    private val voice = RefusalVoice(SERVER_ERROR_PREFIX)
    private val catalog = ShareCatalog(parts.heads, io, voice)
    private val admin = ServerAdmin(server, KeyApi(parts.keys, log, reads.audit), reads, voice)
    private val addresses = ClientAddresses(server.trustedProxies)
    private val signins = SigninRoute(signin, KeyApi(parts.keys, log, reads.audit), addresses, voice, log)
    private val publicRoutes = ServerPublicRoutes(server, parts.keys, parts.people, addresses, tls != null, voice)
    private val keyAdmission = KeyAdmission(parts.keys, addresses, log)
    private val watched = WatchedRelay()
    private val paths = RequestPaths()
    private val admission = TurnAdmission(parts, voice, catalog, watched)
    private val checkins = ClientCheckins(server.client, voice)
    private val minimum = ClientMinimum(server.client, checkins)
    private val aliases = PathAliases(server)
    private val teamDoor = TeamDoorRoute(server, parts, minimum, admission, voice)

    /** How often a streamed turn's client is sent a keepalive while its answer is silent; null sends none. */
    private val keepalive = server.keepaliveSeconds.takeIf { it > 0 }?.seconds
    private val openAi = OpenAiRoute(parts, io, log, keepalive, minimum)
    private val routing = HeadRouting(voice, catalog, admission, watched, minimum, keepalive)
    private val heads = HeadCommandRoute(parts, routing, log)
    private val profile = ProfileRoute(server, parts.heads, io, addresses, secure = tls != null)
    private val telemetry = server.telemetry?.let { TelemetryRelay(it, voice, log) }

    /** The path the telemetry relay answers under (F8). */
    private val otlpRoute = listOf(SERVER_ROOT, OTLP_ROUTE)

    @Volatile
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** Opens the listener on [address], `[server] listen` as boot read it. */
    fun start(address: ServerAddress) {
        val server = embeddedServer(
            Netty,
            serverConfig { module { intercept(ApplicationCallPipeline.Call) { answer(call) } } },
        ) {
            HttpListenerThreads.configure(this)
            enableHttp2 = false
            if (tls == null) {
                connector {
                    host = address.host
                    port = address.port
                }
            } else {
                sslConnector(tls, PEM_ALIAS, { PEM_PASSWORD.toCharArray() }, { PEM_PASSWORD.toCharArray() }) {
                    host = address.host
                    port = address.port
                }
            }
            shutdownGracePeriod = SERVER_STOP_GRACE_MS
        }
        server.start(wait = false)
        engine = server
        val scheme = if (tls == null) "http" else "https"
        val commands = this.server.commands.joinToString(", ")
        log("[server] serving $commands to key holders on $scheme://${address.host}:${address.port}\n")
    }

    fun stop() {
        engine?.stop(SERVER_STOP_GRACE_MS, SERVER_STOP_TIMEOUT_MS)
        engine = null
    }

    private suspend fun answer(call: ApplicationCall) {
        // An alias (#791) is read as the path it stands for once, here, so every route after judges that path.
        val path = paths.read(call)?.let(aliases::rewrite)
        if (answeredOpen(call, path)) return
        // A request refused before its route is known is still refused in the shape its client reads.
        val voiced = if (path != null && openAi.asks(call, path)) openAi.voice else voice
        val key = keyAdmission.admitted(call, voiced) ?: return
        if (path == null) noSuchPath(call) else routed(call, key, path)
        parts.keys.keepUse()
    }

    /** Routes that handle their own auth or need none: health, sign-in, forward_auth, and install script. */
    private suspend fun answeredOpen(call: ApplicationCall, path: List<String>?): Boolean = when {
        path == null -> false
        call.request.httpMethod == HttpMethod.Get && path == listOf(HEALTH_ROUTE) -> {
            call.respondText(HEALTH_BODY, ContentType.Application.Json)
            true
        }
        signins.asks(path) -> {
            signins.answer(call, path)
            true
        }
        else -> publicRoutes.answered(call, path)
    }

    /** [path] asked with a live [key]: what the server offers, its own API, or one of its commands. */
    private suspend fun routed(call: ApplicationCall, key: ServerKey, path: List<String>) {
        when {
            path.isEmpty() && call.request.httpMethod == HttpMethod.Get -> catalog.directory(call)
            path.isEmpty() -> noSuchPath(call)
            profile.asks(call, path) -> profile.answer(call, key)
            checkins.asks(call, path) -> checkins.answer(call, key)
            path.take(2) == otlpRoute -> otlp(call, key, path.drop(2))
            else -> beyond(call, key, path)
        }
    }

    /** [path] is none of the listener's own routes: the server's admin API, the team's door, or one of its commands. */
    private suspend fun beyond(call: ApplicationCall, key: ServerKey, path: List<String>) {
        when (path.first()) {
            SERVER_ROOT -> admin.answer(call, key, path.drop(1))
            DOOR_SEGMENT -> teamDoor.answer(call, key, path.drop(1))
            else -> if (!openAi.answered(call, key, path)) heads.answer(call, key, path)
        }
    }

    /** [rest], after `/server/otlp`, relayed to the operator's collector with [key]'s person and team on it; a server
     *  with no relay has no such path (F8). */
    private suspend fun otlp(call: ApplicationCall, key: ServerKey, rest: List<String>) {
        val relay = telemetry ?: return noSuchPath(call)
        relay.answer(call, rest, key.person, parts.people.team(key.person, server.teams))
    }

    private suspend fun noSuchPath(call: ApplicationCall) {
        voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on ${parts.heads.machine}")
    }
}

/** What every error the server listener answers with starts with. */
internal const val SERVER_ERROR_PREFIX: String = "splice server: "

private const val HEALTH_ROUTE = "health"
private const val HEALTH_BODY = """{"status":"ok"}"""

/** The first path segment of the server's own routes; no command may be named it. */
internal const val SERVER_ROOT: String = "server"

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and its client's Claude Code retries it as it retries any cut turn.
private const val SERVER_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SERVER_STOP_TIMEOUT_MS = 5_000L
