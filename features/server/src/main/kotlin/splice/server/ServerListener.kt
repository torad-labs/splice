// NEW: server mode (docs/specs/server-mode.md, F1). The listener that makes this machine a team's front door: it
// opens only when splice.toml has a `[server]`, on the address that table names, any address included, which is why
// the wall kt-embedded-server-loopback exempts this file, by that spec's decision. LAN share answers anyone on the
// person's own network and holds no identity; this listener answers only keys, each one a person's, from anywhere:
//
//   GET  /health                   open: the listener is up. Nothing else is answered without a key.
//   GET  /                         what this server offers the key's holder: each command, its head and models
//   GET  /<command>/launch         what a laptop's launch of that command needs (as share mode serves it)
//   ANY  /<command>/v1/...         relayed to the head on loopback with this machine's turn key in place of the
//                                  person's key, and the answer streamed back as it arrives
//   POST /<command>/v1/chat/completions, GET /<command>/v1/models
//                                  an OpenAI-style client's, for a command whose provider speaks openai-chat: relayed
//                                  to that provider's own server, refusals in OpenAI's error shape (OpenAiRoute)
//   ANY  /server/api/...           the server's own API, for a key whose person is an admin (ServerAdmin)
//
// A key arrives as `x-api-key` or as `Authorization: Bearer`. A missing, unknown, revoked or expired one is a 401
// `authentication_error` that says which and never repeats the key, and nothing reaches a head. Only the commands
// `[server] commands` lists are served; any other name is 404 naming those. A `POST /<command>/v1/messages` is a turn:
// it counts against its person's in-flight and per-minute caps (PersonLimits), and one past a cap is a 429
// `rate_limit_error` with `Retry-After` (TurnAdmission). Every error is in the Anthropic shape, so Claude Code prints
// its message.
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
// JSON body as before.
//
// A path is read the way a head reads one, percent-escapes decoded, and a head is handed the path as it was read
// (RequestPaths): what a head serves as a turn is what the caps counted, however the client spelled it.
//
// HTTP/2 is off: Claude Code speaks HTTP/1.1, and a turn's client leaving is read off its connection (WatchedRelay),
// which is one request's on HTTP/1.1.
package splice.server

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
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import splice.core.auth.BearerScheme
import splice.core.pool.PREFERRED_UPSTREAM_HEADER
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.http.listen.HttpListenerThreads
import splice.share.HEAD_API_ROOT
import splice.share.HEAD_LAUNCH_ROUTE
import splice.share.HeadTurn
import splice.share.RefusalVoice
import splice.share.ShareCatalog
import splice.share.ShareRelay
import splice.share.SharedHead
import splice.share.SharedHeads
import java.security.KeyStore
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** What the listener serves from: the keys it answers, each person's limits, the heads `[server] commands` names,
 *  the relay that hands their turns to those heads, its TLS identity when it speaks HTTPS itself, each person's
 *  settings, and the servers behind those heads' providers. */
internal data class ServerParts(
    val keys: ServerKeys,
    val limits: PersonLimits,
    val heads: SharedHeads,
    val relay: ShareRelay,
    val tls: KeyStore? = null,
    val people: ServerPeople,
    val upstreams: UpstreamApi,
)

internal class ServerListener(
    private val server: ServerConfig,
    private val parts: ServerParts,
    /** Blocking work runs here: reading a head's launch, and the relay's JDK I/O. */
    io: CoroutineDispatcher,
    private val log: LogSink,
) {
    private val voice = RefusalVoice(SERVER_ERROR_PREFIX)
    private val catalog = ShareCatalog(parts.heads, io, voice)
    private val admin = ServerAdmin(server, KeyApi(parts.keys, log), voice)
    private val addresses = ClientAddresses(server.trustedProxies)
    private val refusedCallers = RefusedCallers(log)
    private val watched = WatchedRelay()
    private val paths = RequestPaths()
    private val admission = TurnAdmission(parts, voice)
    private val bound = ProviderBound(parts.keys, log)
    private val openAi = OpenAiRoute(parts, io, log)

    /** How often a streamed turn's client is sent a keepalive while its answer is silent; null sends none. */
    private val keepalive = server.keepaliveSeconds.takeIf { it > 0 }?.seconds

    /** The path, after the command, of the one request that runs a turn on the Messages route. */
    private val turnPath = listOf(HEAD_API_ROOT, "messages")

    @Volatile
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** Opens the listener on [address], `[server] listen` as boot read it. */
    fun start(address: ServerAddress) {
        val tls = parts.tls
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
        val path = paths.read(call)
        val read = call.request.httpMethod == HttpMethod.Get
        if (read && path == listOf(HEALTH_ROUTE)) return call.respondText(HEALTH_BODY, ContentType.Application.Json)
        // A request refused before its route is known is still refused in the shape its client reads.
        val key = admitted(call, if (path != null && openAi.asks(call, path)) openAi.voice else voice) ?: return
        if (path == null) noSuchPath(call) else routed(call, key, path)
        parts.keys.keepUse()
    }

    /** [path] asked with a live [key]: what the server offers, its own API, or one of its commands. */
    private suspend fun routed(call: ApplicationCall, key: ServerKey, path: List<String>) {
        when {
            path.isEmpty() && call.request.httpMethod == HttpMethod.Get -> catalog.directory(call)
            path.isEmpty() -> noSuchPath(call)
            path.first() == SERVER_ROOT -> admin.answer(call, key, path.drop(1))
            else -> if (!openAi.answered(call, key, path)) command(call, key, path)
        }
    }

    /** The key [call] presents, once it is a live one; else null with the 401 answered in [voice], in words that say
     *  which of missing, unknown, revoked and expired it was and never repeat the key. */
    private suspend fun admitted(call: ApplicationCall, voice: RefusalVoice): ServerKey? {
        val headers = call.request.headers
        val presented = headers[API_KEY_HEADER]?.trim()?.takeIf(String::isNotEmpty)
            ?: BearerScheme.bearerToken(headers[HttpHeaders.Authorization])
        val why = when (val verdict = parts.keys.admit(presented)) {
            is KeyVerdict.Admitted -> return verdict.key
            is KeyVerdict.Revoked -> "this key was revoked; ask the server's operator for a new one"
            is KeyVerdict.Expired -> expired(verdict.key)
            KeyVerdict.Unknown -> if (presented == null) NO_KEY_SENT else "this key is not one this server issued"
        }
        val forwarded = headers.getAll(HttpHeaders.XForwardedFor)?.joinToString(",")
        refusedCallers.refused(addresses.of(call.request.origin.remoteAddress, forwarded), why)
        voice.refuse(call, HttpStatus.UNAUTHORIZED, why)
        return null
    }

    private fun expired(key: ServerKey): String {
        val since = key.expiresAt?.let { " on ${Instant.ofEpochMilli(it)}" }.orEmpty()
        return "this key expired$since; ask the server's operator for a new one"
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

    /** Relays [path] to [head] for [key]'s person. A turn takes one of the person's slots first, or is refused. */
    private suspend fun turn(call: ApplicationCall, key: ServerKey, head: SharedHead, path: List<String>) {
        if (!head.running()) return catalog.notRunning(call, head)
        val counted = call.request.httpMethod == HttpMethod.Post && path == turnPath
        val slot = if (counted) admission.slot(call, key, head) ?: return else null
        val preferred = parts.people.of(key.person).preferred
        val named = listOfNotNull(preferred?.let { PREFERRED_UPSTREAM_HEADER to it }).toMap()
        // The head sends its provider the headers `forward_headers` names, and knows none of this server's keys.
        val held = bound.keyed(call, parts.upstreams.forwarded(head.key))
        val turn = HeadTurn(head, paths.spell(path) + paths.query(call), named, keepalive, held)
        watched.turn(call, slot) { parts.relay.turn(call, turn) }
    }

    private suspend fun noSuchPath(call: ApplicationCall) {
        voice.refuse(call, HttpStatus.NOT_FOUND, "no such path on ${parts.heads.machine}")
    }
}

/** What every error the server listener answers with starts with. */
internal const val SERVER_ERROR_PREFIX: String = "splice server: "

// The header Claude Code and the Anthropic SDKs send an API key in.
private const val API_KEY_HEADER = "x-api-key"

private const val HEALTH_ROUTE = "health"
private const val HEALTH_BODY = """{"status":"ok"}"""

// The first path segment of the server's own routes; no command may be named it.
private const val SERVER_ROOT = "server"

private const val NO_KEY_SENT = "no key was sent; send yours as x-api-key or as Authorization: Bearer"

// why: a stop lets requests already in the listener finish for a second before it closes; a relayed turn still
// streaming is cut, and its client's Claude Code retries it as it retries any cut turn.
private const val SERVER_STOP_GRACE_MS = 1_000L

// why: the most a daemon stop waits on this listener in all, so a relay stuck on a head cannot hold up a restart.
private const val SERVER_STOP_TIMEOUT_MS = 5_000L
