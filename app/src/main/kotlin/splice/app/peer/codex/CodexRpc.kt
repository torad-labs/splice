// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): the JSON-RPC dialog over a codex peer's WebSocket to
// its app-server. One reader takes every message the app-server sends: an answer goes to the request waiting on its id,
// a notification to [CodexRpc.notifications], and a request the app-server makes of this client is refused, as the
// helper answers no approval and grants no tool. A request whose connection closed before it went out says so, apart
// from one that went out and got no answer, because only the first is certainly not in the thread.
package splice.app.peer.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// why: JSON-RPC 2.0's "method not found", the answer to a request this client does not serve.
private const val METHOD_NOT_FOUND = -32_601

private const val JSONRPC = "jsonrpc"
private const val VERSION = "2.0"
private const val ID = "id"
private const val METHOD = "method"
private const val PARAMS = "params"
private const val RESULT = "result"
private const val FAILURE = "error"
private const val MESSAGE = "message"
private const val CODE = "code"

/** A notification the app-server sent: its method and params. */
internal data class CodexEvent(val method: String, val params: JsonObject?)

/** What a request came to. */
internal sealed class CodexAnswer {
    data class Result(val value: JsonElement) : CodexAnswer()

    data class Error(val message: String) : CodexAnswer()

    /** No answer: [sent] when the request went out first, so the app-server may have acted on it. */
    data class Lost(val reason: String, val sent: Boolean) : CodexAnswer()
}

internal class CodexRpc(private val socket: CodexSocket, private val io: CoroutineDispatcher) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<CodexAnswer>>()
    private val ids = AtomicLong(1L)
    private val events = Channel<CodexEvent>(Channel.UNLIMITED)

    @Volatile
    private var open: Boolean = true

    /** Every notification, in order; it ends when the connection does. */
    val notifications: ReceiveChannel<CodexEvent> get() = events

    /** Reads until the connection ends, then fails every request still waiting. Answers why it ended. */
    suspend fun read(): String {
        val ended = try {
            var message = runInterruptible(io) { socket.receive() }
            while (message != null) {
                dispatch(message)
                message = runInterruptible(io) { socket.receive() }
            }
            "the app-server closed the connection"
        } catch (failure: IOException) {
            "the connection to the app-server failed: ${failure.message}"
        }
        closed(ended)
        return ended
    }

    /** Sends [method] with [params] and waits at most [timeoutMs] for its answer. */
    suspend fun request(method: String, params: JsonObject, timeoutMs: Long): CodexAnswer {
        val id = ids.getAndIncrement()
        val answer = CompletableDeferred<CodexAnswer>()
        pending[id] = answer
        val message = buildJsonObject {
            put(JSONRPC, VERSION)
            put(ID, id)
            put(METHOD, method)
            put(PARAMS, params)
        }
        val unsent = if (open) send(message) else "the connection to the app-server had closed"
        if (unsent != null) {
            pending.remove(id)
            return CodexAnswer.Lost(unsent, sent = false)
        }
        val got = withTimeoutOrNull(timeoutMs) { answer.await() }
        pending.remove(id)
        val waited = Duration.ofMillis(timeoutMs).toSeconds()
        return got ?: CodexAnswer.Lost("the app-server did not answer $method in $waited s", sent = true)
    }

    /** Sends a notification; why it could not go, or null. */
    suspend fun notify(method: String): String? = send(
        buildJsonObject {
            put(JSONRPC, VERSION)
            put(METHOD, method)
        },
    )

    fun close() {
        open = false
        socket.close()
    }

    private suspend fun send(message: JsonObject): String? = try {
        runInterruptible(io) { socket.send(JsonWire.string(message)) }
        null
    } catch (failure: IOException) {
        "the request could not be written: ${failure.message}"
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private suspend fun dispatch(text: String) {
        val message = parse(text) ?: return
        val id = message[ID]?.takeIf { it != JsonNull }
        val method = JsonScalars.str(message, METHOD)
        when {
            method != null && id != null -> refuse(id)
            method != null -> events.trySend(CodexEvent(method, message[PARAMS] as? JsonObject))
            else -> answered(message)
        }
    }

    private fun answered(message: JsonObject) {
        val id = JsonScalars.long(message, ID) ?: return
        val error = message[FAILURE] as? JsonObject
        val result = message[RESULT]
        val answer = when {
            error != null -> CodexAnswer.Error(JsonScalars.str(error, MESSAGE) ?: "the app-server refused the request")
            result != null -> CodexAnswer.Result(result)
            else -> CodexAnswer.Error("the app-server answered with neither a result nor an error")
        }
        pending.remove(id)?.complete(answer)
    }

    /** A request the app-server made of this client, such as an approval: the helper serves none. */
    private suspend fun refuse(id: JsonElement) {
        send(
            buildJsonObject {
                put(JSONRPC, VERSION)
                put(ID, id)
                put(
                    FAILURE,
                    buildJsonObject {
                        put(CODE, METHOD_NOT_FOUND)
                        put(MESSAGE, JsonPrimitive("a Splice codex peer answers no requests from the app-server"))
                    },
                )
            },
        )
    }

    private fun closed(reason: String) {
        open = false
        events.close()
        pending.values.forEach { it.complete(CodexAnswer.Lost(reason, sent = true)) }
        pending.clear()
    }
}
