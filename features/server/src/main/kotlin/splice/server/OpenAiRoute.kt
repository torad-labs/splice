// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). The same server and the same keys, for a client that
// speaks OpenAI's API (a script, another tool). A served command whose provider speaks openai-chat answers
//
//   POST /<command>/v1/chat/completions   a chat completion, relayed to the provider's server as the client sent it
//   GET  /<command>/v1/models             that server's own list of models
//   GET  /<command>/v1/models/<id>        one model of that list, or a 404 for an id it does not list (ModelLookup)
//
// Such a request does not pass through the command's head, which speaks Anthropic's API to Claude Code. It goes to the
// provider's own server, with the provider's headers and key and, of the client's own headers, only the ones the
// provider's `forward_headers` names (ProviderBound): the person's key stays here, in whatever header it came. The
// answer comes back as that server gives it: a stream event by event, each as it is written. Two things are the
// relay's own, and the daemon's ChatServer does both in the chat dialect's words: a streamed request always asks the
// server for its usage, and the frame that carries only that usage reaches a client only when it asked for it; and a
// stream the server ended whole ends with `[DONE]`.
//
// An answer the server breaks off partway is an error its client sees, never a quiet end (HeadAnswers). A stream
// ends, after the events already relayed, with one error event in OpenAI's shape (`data: {"error": {...}}`) and no
// `[DONE]`, which the OpenAI SDKs raise; an answer that is no stream is a 502 with OpenAI's error body.
//
// Of the server's answer the client gets what the answer is for: its status, its body, the body's type and, on a
// refusal, its `Retry-After`. The headers the server sends for itself (a cookie, its request ids) stay here.
//
// A chat completion is a turn. It takes one of its person's slots under the same caps as a turn on the Messages route,
// and a client that leaves gives the slot back. A provider that is several servers is asked on the one its pool gives
// the person (their preferred one while it is healthy), and when that one takes no connection, on the next, the way a
// head's own turn moves on (ProviderWay). Every refusal here is in OpenAI's error shape, the 401 of a missing key
// included: that is the shape the asking client reads.
//
// With `[server] keepalive_seconds` a streamed completion is kept alive through a long prefill as a streamed turn is
// (F4, openai-keepalive): when the provider's server has not started its answer within a second, the stream is opened
// to the client then and carries an SSE comment, which OpenAI's SDKs skip, at least that often until the answer comes
// and whenever it falls silent after. A server that has sent its response headers and no chunk has not started it:
// some send those at once and prefill after. The answer follows as it comes, ending with `[DONE]`. The status went
// out as 200 by then, so a refusal or a failure of the server's after that is one error event in OpenAI's shape, the
// server's own error where it gave one. A completion that asks for no stream is one JSON body, and carries none.
//
// Claude Code asks a command for its models too, of the head and in Anthropic's shape. The two are told apart by the
// header every Anthropic client sends: a list or a lookup asked without `anthropic-version` is an OpenAI-style
// client's. A command whose provider speaks another dialect has no such route, and its head answers these paths as it
// always did.
package splice.server

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.core.util.LogSink
import splice.core.wire.ANTHROPIC_VERSION_HEADER
import splice.server.ledger.LedgerTurn
import splice.server.ledger.ROUTE_CHAT
import splice.share.HEAD_API_ROOT
import splice.share.OutwardAsk
import splice.share.RefusalShape
import splice.share.RefusalVoice
import splice.share.ShareCatalog
import splice.share.SharedHead
import kotlin.time.Duration

internal class OpenAiRoute(
    private val parts: ServerParts,
    /** Blocking work runs here: reading the provider's key, and the relay's JDK I/O. */
    private val io: CoroutineDispatcher,
    log: LogSink,
    /** How often a streamed completion's client is sent a keepalive while its answer is silent; null sends none. */
    private val keepalive: Duration?,
) {
    /** How this route refuses, the 401 of a request that names it included. */
    val voice = RefusalVoice(SERVER_ERROR_PREFIX, RefusalShape.OPENAI)
    private val catalog = ShareCatalog(parts.heads, io, voice)
    private val relay = parts.carriers.relay.speaking(voice)
    private val bound = ProviderBound(parts.keys, log)
    private val watched = WatchedRelay()
    private val admission = TurnAdmission(parts, voice, catalog, watched)
    private val paths = RequestPaths()
    private val chatPath = listOf(HEAD_API_ROOT, "chat", "completions")
    private val modelsPath = listOf(HEAD_API_ROOT, "models")

    /** One request on this route: whose it is, the command it names, and that command's provider. */
    private data class Asked(val key: ServerKey, val head: SharedHead, val server: ChatServer)

    /** What a request asks of the provider: the route of its API, as its [path] under the API root, and for a lookup
     *  of one model the [model] it names, which is read out of the list that path answers. */
    private data class Api(val path: String, val model: String? = null)

    /** Whether [path], asked the way [call] asks it, is an OpenAI-style client's. */
    fun asks(call: ApplicationCall, path: List<String>): Boolean = api(call, path) != null

    /** Answers [path] for [key]'s person when it is this route's. False, with nothing answered, when it is not:
     *  another path, or a command whose provider does not speak openai-chat. */
    suspend fun answered(call: ApplicationCall, key: ServerKey, path: List<String>): Boolean {
        val api = api(call, path) ?: return false
        val head = catalog.named(call, path.first()) ?: return true
        val server = parts.upstreams.chat(head.key)
        if (server != null) answer(call, Asked(key, head, server), api)
        return server != null
    }

    /** [asked] for the provider's [api]: a completion as the turn it is, the models as they are asked. */
    private suspend fun answer(call: ApplicationCall, asked: Asked, api: Api) {
        when {
            api.path == CHAT_API ->
                admission.served(call, asked.key, HeadCommand(asked.head), ROUTE_CHAT) { turn ->
                    relayed(call, asked, api, turn)
                }
            !asked.head.running() -> catalog.notRunning(call, asked.head)
            else -> watched.turn(call, slot = null) { relayed(call, asked, api) }
        }
    }

    /** What [path] asks of a provider's API, or null when it is nothing this route answers. */
    private fun api(call: ApplicationCall, path: List<String>): Api? {
        val method = call.request.httpMethod
        val rest = path.drop(1)
        // Claude Code and the Anthropic SDKs send this header on every request, and no OpenAI-style client does.
        val models = method == HttpMethod.Get && ANTHROPIC_VERSION_HEADER !in call.request.headers
        return when {
            method == HttpMethod.Post && rest == chatPath -> Api(CHAT_API)
            models && rest == modelsPath -> Api(MODELS_API)
            // An id may hold a slash (`org/model`), which a client writes as it is: all that follows is the id.
            models && rest.take(modelsPath.size) == modelsPath ->
                Api(MODELS_API, model = rest.drop(modelsPath.size).joinToString("/"))
            else -> null
        }
    }

    /** [call]'s request, asked of the provider's [api] and answered as the provider's server answers it, or as no
     *  server of the provider's took it. A completion's [turn] learns what went and which way. */
    private suspend fun relayed(call: ApplicationCall, asked: Asked, api: Api, turn: LedgerTurn? = null) {
        val sent = relay.sent(call, asked.head) ?: return
        val chat = if (api.path == CHAT_API) asked.server.chat(sent) else ChatAsk(sent, stream = null)
        val own = withContext(io) { asked.server.headers() }
        val headers = bound.headers(call, parts.upstreams.forwarded(asked.head.key), own)
        val lookup = api.model?.let { ModelLookup(it, asked.head.command) }
        // Only an answer asked for as a stream is kept alive: a comment has no place in any other body.
        val whole = lookup ?: chat.whole
        val ask = OutwardAsk(chat.body, headers, chat.stream, whole, keepalive?.takeIf { chat.stream != null })
        val path = api.path + bound.query(paths.query(call))
        val preferred = parts.people.of(asked.key.person).preferred
        val way = ProviderWay(asked.head, path, asked.server.base, parts.upstreams, preferred)
        turn?.chat = chat
        turn?.way = way
        try {
            relay.outward(call, ask, way)
        } finally {
            way.end()
        }
    }
}

// The routes of a provider's API this route relays to, as they follow its API root.
private const val CHAT_API = "/chat/completions"
private const val MODELS_API = "/models"
