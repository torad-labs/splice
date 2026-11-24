// NEW: server mode (docs/specs/server-mode.md, F1). The listener that makes this machine a team's front door: it
// opens only when splice.toml has a `[server]`, on the address that table names, any address included, which is why
// the wall kt-embedded-server-loopback exempts this file, by that spec's decision. LAN share answers anyone on the
// person's own network and holds no identity; this listener answers only keys, each one a person's, from anywhere:
//
//   GET  /health                   open: the listener is up (200 {"status":"ok"}), or draining at stop
//                                  (503 {"status":"draining"}), so a load balancer sends it no more turns.
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
// (ClientMinimum).
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
// (RequestPaths): what a head serves as a turn is what the caps counted, however the client spelled it.
//
// A stop drains first (#791, `[server] drain_seconds`): from its first moment every new turn is a 503 with
// `Retry-After` and `/health` says draining, and the stop waits, up to the drain's length, for the turns in flight to
// end before it closes the listener. With no drain the listener gives them its one second of grace, as before.
//
// With `[server] stream_idle_seconds` a streamed turn whose answer falls silent that long after its first event is
// ended with an error event, which Claude Code retries, instead of hanging (HeadAnswers). Without it, or at 0, a
// silence is waited out however long: a model may think in silence.
//
// HTTP/2 is off: Claude Code speaks HTTP/1.1, and a turn's client leaving is read off its connection (WatchedRelay),
// which is one request's on HTTP/1.1.
package splice.server

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import splice.core.pool.PREFERRED_UPSTREAM_HEADER
import splice.core.topology.DOOR_SEGMENT
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.http.listen.HttpListenerThreads
import splice.server.caller.ClientAddresses
import splice.server.caller.KeyAdmission
import splice.server.ledger.ROUTE_MESSAGES
import splice.server.signin.SigninParts
import splice.server.signin.SigninRoute
import splice.server.telemetry.OTLP_ROUTE
import splice.server.telemetry.ResourceStamp
import splice.server.telemetry.TelemetryRelay
import splice.server.web.ServerPublicRoutes
import splice.share.HEAD_API_ROOT
import splice.share.HEAD_LAUNCH_ROUTE
import splice.share.HeadTurn
import splice.share.RefusalVoice
import splice.share.ShareCatalog
import splice.share.SharedHead
import java.security.KeyStore
import java.util.concurrent.TimeUnit
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
    private val bound = ProviderBound(parts.keys, log)
    private val minimum = ClientMinimum(server.client)
    private val teamDoor = TeamDoorRoute(server, parts, minimum, admission, voice)

    /** How often a streamed turn's client is sent a keepalive while its answer is silent; null sends none. */
    private val keepalive = server.keepaliveSeconds.takeIf { it > 0 }?.seconds

    /** How long a streamed answer may fall silent after its first word before it is ended; 0 waits however long. */
    private val streamIdleMs = TimeUnit.SECONDS.toMillis(server.streamIdleSeconds.toLong())
    private val openAi = OpenAiRoute(parts, io, log, keepalive, streamIdleMs)
    private val profile = ProfileRoute(server, parts.heads, io, addresses, secure = tls != null)
    private val telemetry = server.telemetry?.let { TelemetryRelay(it, voice, log) }

    /** The path the telemetry relay answers under (F8). */
    private val otlpRoute = listOf(SERVER_ROOT, OTLP_ROUTE)

    /** The path, after the command, of the one request that runs a turn on the Messages route. */
    private val turnPath = listOf(HEAD_API_ROOT, "messages")

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

    /** Drains, then closes the listener: new turns are refused and `/health` says draining from now, and the turns in
     *  flight are given up to `[server] drain_seconds` to end before the listener closes (#791). */
    suspend fun stop() {
        val engine = engine ?: return
        val drainMs = TimeUnit.SECONDS.toMillis(server.drainSeconds.toLong())
        if (!parts.limits.load.drain(drainMs) && drainMs > 0) {
            val left = parts.limits.load.inflight()
            log("[server] stop: $left turn(s) still in flight after the drain; they are cut\n")
        }
        engine.stop(SERVER_STOP_GRACE_MS, SERVER_STOP_TIMEOUT_MS)
        this.engine = null
    }

    private suspend fun answer(call: ApplicationCall) {
        val path = paths.read(call)
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
            if (parts.limits.load.draining) {
                call.respondText(DRAINING_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondText(HEALTH_BODY, ContentType.Application.Json)
            }
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
            path.take(2) == otlpRoute -> otlp(call, key, path.drop(2))
            else -> beyond(call, key, path)
        }
    }

    /** [path] is none of the listener's own routes: the server's admin API, the team's door, or one of its commands. */
    private suspend fun beyond(call: ApplicationCall, key: ServerKey, path: List<String>) {
        when (path.first()) {
            SERVER_ROOT -> admin.answer(call, key, path.drop(1))
            DOOR_SEGMENT -> teamDoor.answer(call, key, path.drop(1))
            else -> if (!openAi.answered(call, key, path)) command(call, key, path)
        }
    }

    /** [rest], after `/server/otlp`, relayed to the operator's collector with [key]'s person and team on it; a server
     *  with no relay has no such path (F8). */
    private suspend fun otlp(call: ApplicationCall, key: ServerKey, rest: List<String>) {
        val relay = telemetry ?: return noSuchPath(call)
        relay.answer(call, rest, ResourceStamp(key.person, parts.people.team(key.person, server.teams)))
    }

    /** [path] names a served command first: its turns, or its launch. */
    private suspend fun command(call: ApplicationCall, key: ServerKey, path: List<String>) {
        val head = catalog.named(call, path.first()) ?: return
        val rest = path.drop(1)
        when {
            rest.firstOrNull() == HEAD_API_ROOT -> turn(call, key, head, rest)
            rest == listOf(HEAD_LAUNCH_ROUTE) && call.request.httpMethod == HttpMethod.Get -> catalog.launch(call, head)
            else -> noSuchPath(call)
        }
    }

    /** Relays [path] to [head] for [key]'s person. A turn takes one of the person's slots first, or is refused, and
     *  leaves its row in the ledger however it ends; one from an older laptop is its head's to answer (F9). */
    private suspend fun turn(call: ApplicationCall, key: ServerKey, head: SharedHead, path: List<String>) {
        val isTurn = call.request.httpMethod == HttpMethod.Post && path == turnPath
        // An older laptop's turn is answered by its head with what to upgrade to, and takes no slot (F9).
        val notice = if (isTurn) minimum.notice(call) else null
        if (!isTurn || notice != null) {
            if (!head.running()) return catalog.notRunning(call, head)
            val asked = headTurn(call, key, head, path, notice.orEmpty())
            watched.turn(call, slot = null) { parts.carriers.relay.turn(call, asked) }
            return
        }
        admission.served(call, key, HeadCommand(head), ROUTE_MESSAGES) { turn ->
            val asked = headTurn(call, key, head, path, mapOf(parts.books.turns.named(turn)))
            parts.carriers.relay.turn(call, asked)
        }
    }

    /** [path] as [head] is handed it for [key]'s person, with [named] beside the upstream they prefer. */
    private fun headTurn(
        call: ApplicationCall,
        key: ServerKey,
        head: SharedHead,
        path: List<String>,
        named: Map<String, String>,
    ): HeadTurn {
        val preferred = parts.people.of(key.person).preferred
        val chosen = listOfNotNull(preferred?.let { PREFERRED_UPSTREAM_HEADER to it }).toMap()
        // The head sends its provider the headers `forward_headers` names, and knows none of this server's keys.
        val held = bound.keyed(call, parts.upstreams.forwarded(head.key))
        return HeadTurn(head, paths.spell(path) + paths.query(call), chosen + named, keepalive, held, streamIdleMs)
    }

    private suspend fun noSuchPath(call: ApplicationCall) {
        voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on ${parts.heads.machine}")
    }
}

/** What every error the server listener answers with starts with. */
internal const val SERVER_ERROR_PREFIX: String = "splice server: "

private const val HEALTH_ROUTE = "health"
private const val HEALTH_BODY = """{"status":"ok"}"""
private const val DRAINING_BODY = """{"status":"draining"}"""

/** The first path segment of the server's own routes; no command may be named it. */
internal const val SERVER_ROOT: String = "server"

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and its client's Claude Code retries it as it retries any cut turn.
private const val SERVER_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SERVER_STOP_TIMEOUT_MS = 5_000L
