// NEW: server mode (docs/specs/server-mode.md, F4, openai-route). The same server and the same keys, for a client that
// speaks OpenAI's API (a script, another tool). A served command whose provider speaks openai-chat answers
//
//   POST /<command>/v1/chat/completions   a chat completion, relayed to the provider's server as the client sent it
//   GET  /<command>/v1/models             that server's own list of models
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
// head's own turn moves on (PoolWalk). Every refusal here is in OpenAI's error shape, the 401 of a missing key
// included: that is the shape the asking client reads.
//
// Claude Code asks a command for its models too, of the head and in Anthropic's shape. The two are told apart by the
// header every Anthropic client sends: a list asked without `anthropic-version` is an OpenAI-style client's. A command
// whose provider speaks another dialect has no such route, and its head answers these paths as it always did.
package splice.server

import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.core.pool.PoolWalk
import splice.core.pool.UpstreamPool
import splice.core.util.LogSink
import splice.core.wire.ANTHROPIC_VERSION_HEADER
import splice.core.wire.HttpStatus
import splice.share.HEAD_API_ROOT
import splice.share.OutwardAsk
import splice.share.OutwardHeaders
import splice.share.RefusalShape
import splice.share.RefusalVoice
import splice.share.ShareCatalog
import splice.share.SharedHead
import java.net.URI

internal class OpenAiRoute(
    private val parts: ServerParts,
    /** Blocking work runs here: reading the provider's key, and the relay's JDK I/O. */
    private val io: CoroutineDispatcher,
    log: LogSink,
) {
    /** How this route refuses, the 401 of a request that names it included. */
    val voice = RefusalVoice(SERVER_ERROR_PREFIX, RefusalShape.OPENAI)
    private val catalog = ShareCatalog(parts.heads, io, voice)
    private val relay = parts.relay.speaking(voice)
    private val admission = TurnAdmission(parts, voice)
    private val bound = ProviderBound(parts.keys, log)
    private val watched = WatchedRelay()
    private val paths = RequestPaths()
    private val chatPath = listOf(HEAD_API_ROOT, "chat", "completions")
    private val modelsPath = listOf(HEAD_API_ROOT, "models")

    /** One request on this route: whose it is, the command it names, and that command's provider. */
    private data class Asked(val key: ServerKey, val head: SharedHead, val server: ChatServer)

    /** One request ready for a server: its [path] under the server's API root, the [headers] it carries, and its
     *  body with what reads its answer. */
    private class Ready(val path: String, val headers: OutwardHeaders, val ask: ChatAsk) {
        /** This request for the server whose API root is [base], which its client hears named as [server]. */
        fun at(base: String, server: String) = OutwardAsk(URI.create(base + path), headers, server, ask.stream)
    }

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

    /** [asked] for the provider's [api]: a completion as the turn it is, the models list as it is asked. */
    private suspend fun answer(call: ApplicationCall, asked: Asked, api: String) {
        when {
            !asked.head.running() -> catalog.notRunning(call, asked.head)
            api == CHAT_API -> chat(call, asked)
            else -> watched.turn(call, slot = null) { relayed(call, asked, api) }
        }
    }

    /** The route of a provider's API that [path] asks for, or null when it is not one this route answers. */
    private fun api(call: ApplicationCall, path: List<String>): String? {
        // Claude Code and the Anthropic SDKs send this header on every request, and no OpenAI-style client does.
        val anthropic = ANTHROPIC_VERSION_HEADER in call.request.headers
        return when (call.request.httpMethod to path.drop(1)) {
            HttpMethod.Post to chatPath -> CHAT_API
            HttpMethod.Get to modelsPath -> MODELS_API.takeUnless { anthropic }
            else -> null
        }
    }

    /** A chat completion: it takes one of its person's slots, or is refused, and holds it until its answer ends or
     *  its client leaves. */
    private suspend fun chat(call: ApplicationCall, asked: Asked) {
        val slot = admission.slot(call, asked.key, asked.head) ?: return
        watched.turn(call, slot) { relayed(call, asked, CHAT_API) }
    }

    /** [call]'s request, asked of the provider's [api] and answered as the provider's server answers it. */
    private suspend fun relayed(call: ApplicationCall, asked: Asked, api: String) {
        val sent = relay.sent(call, asked.head) ?: return
        val ask = if (api == CHAT_API) asked.server.chat(sent) else ChatAsk(sent, stream = null)
        val own = withContext(io) { asked.server.headers() }
        val headers = bound.headers(call, parts.upstreams.forwarded(asked.head.key), own)
        val ready = Ready(api + bound.query(paths.query(call)), headers, ask)
        val pool = parts.upstreams.pool(asked.head.key)
        val server = "the model server behind ${asked.head.command}"
        val taken = if (pool == null) {
            relay.outward(call, ready.at(asked.server.base, server), ask.body)
        } else {
            pooled(call, asked, pool, ready)
        }
        if (taken) return
        val silent = parts.upstreams.noneUp(asked.head.key) ?: "$server is not answering"
        voice.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, silent)
    }

    /** [ready] asked of [pool]'s servers, one at a time, as [PoolWalk] names them: the one the person prefers while it
     *  is healthy, else the healthy one with the least in flight, and when that one takes no connection, the next.
     *  False, with nothing answered, once none is left to ask. */
    private suspend fun pooled(call: ApplicationCall, asked: Asked, pool: UpstreamPool, ready: Ready): Boolean {
        if (!pool.up) return false
        val walk = PoolWalk(pool, parts.people.of(asked.key.person).preferred)
        try {
            do {
                val server = walk.server()
                val named = "upstream ${server.name} of ${pool.provider}"
                if (relay.outward(call, ready.at(server.url, named), ready.ask.body)) return true
            } while (walk.moved())
            return false
        } finally {
            walk.end()
        }
    }
}

// The routes of a provider's API this route relays to, as they follow its API root.
private const val CHAT_API = "/chat/completions"
private const val MODELS_API = "/models"
