// Routing and admission for the team HTTP listener, independent of its socket and engine lifecycle.
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
//   ANY  /server/mod/<name>/...    a mode whose `[modes.<name>] server_routes` is true, told who is asking and never
//                                  the key; with `server_admin_only` only an admin's key reaches it (ModRoute)
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
// A stop drains first (#791, `[server] drain_seconds`): from its first moment every keyed request, a turn on any route
// or anything else a key asks, is a 503 with `Retry-After` in its client's shape, and `/health` says draining; the stop
// waits, up to the drain's length, for every answer the listener has begun, a turn's included, to be written to its
// client's connection before it closes the listener (AnswersInFlight). With no drain the listener gives them its one
// second of grace, as before.
//
// With `[server] stream_idle_seconds` a streamed turn whose answer falls silent that long after its first word is
// ended with an error event, which Claude Code retries, instead of hanging: on a command's routes by the relay
// (HeadAnswers), and on the team's door by the turn's own watchdog (TeamDoorRoute). Without it, or at 0, a silence is
// waited out however long: a model may think in silence.
//
package splice.server

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import splice.core.server.SERVER_HEALTH_ROUTE
import splice.core.server.SERVER_MOD_SEGMENT
import splice.core.server.SERVER_ROOT
import splice.core.topology.DOOR_SEGMENT
import splice.core.topology.ServerConfig
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import splice.server.caller.AdminAccess
import splice.server.caller.ClientAddresses
import splice.server.caller.KeyAdmission
import splice.server.client.ClientCheckins
import splice.server.client.ClientMinimum
import splice.server.load.DrainGate
import splice.server.mod.ModRoute
import splice.server.path.PathAliases
import splice.server.path.RequestPaths
import splice.server.signin.SigninAttempts
import splice.server.signin.SigninRoute
import splice.server.telemetry.OTLP_ROUTE
import splice.server.telemetry.TelemetryRelay
import splice.server.web.BrowserForwardAuth
import splice.server.web.ForwardAuthRoute
import splice.server.web.ServerPublicRoutes
import splice.share.RefusalVoice
import splice.share.ShareCatalog
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

internal class ServerRequests(
    private val server: ServerConfig,
    private val parts: ServerParts,
    secure: Boolean,
    /** The ledger's reports and the live metrics, which an admin reads over the listener. */
    reads: AdminReads,
    /** Blocking work runs here: reading a head's launch, and the relay's JDK I/O. */
    io: CoroutineDispatcher,
    log: LogSink,
    optional: OptionalRoutes,
) {
    private val voice = RefusalVoice(SERVER_ERROR_PREFIX)
    private val catalog = ShareCatalog(parts.heads, io, voice)
    private val addresses = ClientAddresses(server.trustedProxies)
    private val identity = optional.signins
    private val adminAccess = AdminAccess(server, addresses)
    private val keys = KeyApi(parts.keys, log, reads.audit)
    private val attempts = SigninAttempts(addresses, identity.clock)
    private val admin = ServerAdmin(adminAccess, keys, reads, voice)
    private val signins = SigninRoute(identity.cli, keys, attempts, voice, log)
    private val browser = server.forwardAuth?.takeIf { it.browserOrigin != null }?.let { policy ->
        BrowserForwardAuth(
            policy,
            checkNotNull(identity.browser),
            parts.keys,
            adminAccess,
            attempts,
            voice,
            identity.clock,
        )
    }
    private val drain = DrainGate(parts.limits.load)
    private val forwardAuth = ForwardAuthRoute(server, parts.keys, parts.people, adminAccess, browser, voice, drain)
    private val publicRoutes = ServerPublicRoutes(server, addresses, secure, voice, forwardAuth)
    private val keyAdmission = KeyAdmission(parts.keys, addresses, log)
    private val watched = WatchedRelay()
    private val paths = RequestPaths()
    private val admission = TurnAdmission(parts, voice, catalog, watched)
    private val checkins = ClientCheckins(server.client, voice)
    private val minimum = ClientMinimum(server.client, checkins)
    private val aliases = PathAliases(server)
    private val teamDoor = TeamDoorRoute(server, parts, minimum, admission, voice, ProviderBound(parts.keys, log))

    /** How often a streamed turn's client is sent a keepalive while its answer is silent; null sends none. */
    private val keepalive = server.keepaliveSeconds.takeIf { it > 0 }?.seconds

    /** How long a streamed answer may fall silent after its first word before it is ended; 0 waits however long. */
    private val streamIdleMs = TimeUnit.SECONDS.toMillis(server.streamIdleSeconds.toLong())
    private val openAi = OpenAiRoute(parts, io, log, keepalive, minimum, streamIdleMs)
    private val routing = HeadRouting(voice, catalog, admission, watched, minimum, keepalive, streamIdleMs)
    private val heads = HeadCommandRoute(parts, routing, log)
    private val profile = ProfileRoute(server, parts.heads, io, addresses, secure = secure)
    private val telemetry = server.telemetry?.let { TelemetryRelay(it, voice, log) }
    private val mods =
        ModRoute(optional.mods, server.teams, parts.people, adminAccess, voice, io, ServerCredentials(parts.keys))

    /** The path the telemetry relay answers under (F8). */
    private val otlpRoute = listOf(SERVER_ROOT, OTLP_ROUTE)

    suspend fun answer(call: ApplicationCall) {
        // An alias (#791) is read as the path it stands for once, here, so every route after judges that path.
        val path = paths.read(call)?.let(aliases::rewrite)
        if (answeredOpen(call, path)) return
        // A request refused before its route is known is still refused in the shape its client reads.
        val voiced = if (path != null && openAi.asks(call, path)) openAi.voice else voice
        val key = keyAdmission.admitted(call, voiced) ?: return
        when {
            drain.refused(call, voiced) -> Unit
            path == null -> noSuchPath(call)
            else -> routed(call, key, path)
        }
        parts.keys.keepUse()
    }

    /** Routes that handle their own auth or need none: health, sign-in, forward_auth, and install script. */
    private suspend fun answeredOpen(call: ApplicationCall, path: List<String>?): Boolean = when {
        path == null -> false
        call.request.httpMethod == HttpMethod.Get && path == listOf(SERVER_HEALTH_ROUTE) -> {
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
            checkins.asks(call, path) -> checkins.answer(call, key)
            path.take(2) == otlpRoute -> otlp(call, key, path.drop(2))
            else -> beyond(call, key, path)
        }
    }

    /** [path] is none of the listener's own routes: the server's admin API or its modes, the team's door, or one of its
     *  commands. */
    private suspend fun beyond(call: ApplicationCall, key: ServerKey, path: List<String>) {
        when (path.first()) {
            SERVER_ROOT -> if (path.getOrNull(1) == SERVER_MOD_SEGMENT) {
                mods.answer(call, key.person, path.drop(2))
            } else {
                admin.answer(call, key, path.drop(1))
            }
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

private const val HEALTH_BODY = """{"status":"ok"}"""
private const val DRAINING_BODY = """{"status":"draining"}"""
