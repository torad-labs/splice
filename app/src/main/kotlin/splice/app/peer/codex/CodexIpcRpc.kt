package splice.app.peer.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.SafeFailureText
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// why: the desktop IPC discovery handshake must explicitly decline tool ownership.
private const val CLIENT_DISCOVERY_REQUEST = "client-discovery-request"

// why: the peer grants no request initiated by the desktop IPC server.
private const val IPC_NO_HANDLER = "no-handler-for-request"

// why: the desktop handshake requires initialize to originate from this reserved client identity.
private const val INITIAL_CLIENT_ID = "initializing-client"

// why: the timeout covers writing and waiting for a reply; after any write attempt, expiry leaves delivery uncertain.
private const val REQUEST_TIMEOUT_REASON = "the desktop Codex IPC request timed out"

// why: a control broadcast is small, and it must not pin thread attachment behind a stalled local socket writer.
private const val BROADCAST_WRITE_TIMEOUT_MS = 5_000L
private const val BROADCAST_TIMEOUT_REASON = "the desktop Codex IPC broadcast write timed out"

// why: pending requests are settled when the connection is closed or the reader reaches EOF.
private const val CLOSED_REASON = "the desktop Codex IPC connection closed"

// why: malformed framing payloads must end the IPC session rather than leave callers waiting on corrupt state.
private const val INVALID_MESSAGE_REASON = "the desktop Codex IPC message was invalid"

// why: retain a small burst of lifecycle broadcasts without letting an unattended reader grow without bound.
private const val IPC_EVENT_BUFFER = 32

private const val FIELD_TYPE = "type"
private const val FIELD_REQUEST_ID = "requestId"
private const val FIELD_SOURCE_CLIENT_ID = "sourceClientId"
private const val FIELD_VERSION = "version"
private const val FIELD_METHOD = "method"
private const val FIELD_PARAMS = "params"
private const val FIELD_TARGET_CLIENT_ID = "targetClientId"
private const val FIELD_TARGET_CLIENT_IDS = "targetClientIds"
private const val FIELD_TIMEOUT_MS = "timeoutMs"
private const val FIELD_RESULT_TYPE = "resultType"
private const val FIELD_ERROR = "error"

private const val TYPE_REQUEST = "request"
private const val TYPE_BROADCAST = "broadcast"
private const val TYPE_RESPONSE = "response"
private const val TYPE_DISCOVERY_RESPONSE = "client-discovery-response"
private const val RESULT_SUCCESS = "success"

private const val REQUEST_REFUSED_REASON = "the desktop Codex IPC request was refused"
private const val RESPONSE_RESULT_TYPE_REASON = "the desktop Codex IPC response had no result type"

/** An answer to one desktop IPC request. [Lost.sent] distinguishes a definitely unsent request from an uncertain one. */
internal sealed class CodexIpcAnswer {
    data class Result(val value: JsonObject) : CodexIpcAnswer()

    data class Error(val reason: String) : CodexIpcAnswer()

    data class Lost(val reason: String, val sent: Boolean) : CodexIpcAnswer()
}

/** JSON-RPC-shaped desktop IPC over [CodexIpcSocket], including request correlation and lifecycle broadcasts. */
internal class CodexIpcRpc(
    private val socket: CodexIpcSocket,
    private val io: CoroutineDispatcher,
) : AutoCloseable {
    private val json = Json
    private val pending = ConcurrentHashMap<String, Pending>()
    private val broadcasts = Channel<JsonObject>(IPC_EVENT_BUFFER)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var clientId: String = INITIAL_CLIENT_ID

    /** Lifecycle broadcasts not consumed as replies, in arrival order. */
    val events: ReceiveChannel<JsonObject> get() = broadcasts

    /** Reads desktop responses and lifecycle broadcasts until the connection ends. */
    suspend fun read(): String {
        val reason = try {
            while (!closed.get()) {
                val text = runInterruptible(io) { socket.receive() } ?: break
                dispatch(text)
            }
            CLOSED_REASON
        } catch (cancelled: CancellationException) {
            finish(CLOSED_REASON)
            throw cancelled
        } catch (failure: IOException) {
            "the desktop Codex IPC connection failed: ${failure.message}"
        }
        finish(reason)
        return reason
    }

    /** Sends a desktop IPC request and waits up to [timeoutMs] for its matching response. */
    suspend fun request(
        method: String,
        params: JsonObject,
        version: Int,
        timeoutMs: Long,
        targetClientId: String? = null,
    ): CodexIpcAnswer {
        val requestId = UUID.randomUUID().toString()
        val waiting = Pending(requestId, method, CompletableDeferred())
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val deadline = RequestDeadline(timeoutMs, deadlineNanos)
        pending[waiting.requestId] = waiting
        try {
            sendRequest(waiting, params, version, targetClientId, deadline)?.let { return it }
            val response = withTimeoutOrNull(remainingTimeoutMs(deadline.deadlineNanos)) { waiting.answer.await() }
                ?: return CodexIpcAnswer.Lost(REQUEST_TIMEOUT_REASON, sent = true)
            if (response is CodexIpcAnswer.Result && method == "initialize") {
                JsonScalars.str(response.value["result"] as? JsonObject, "clientId")?.let { clientId = it }
            }
            return response
        } finally {
            pending.remove(waiting.requestId, waiting)
        }
    }

    /** Once writing starts, a partial frame may have reached the desktop; preserve that uncertainty. */
    private suspend fun sendRequest(
        waiting: Pending,
        params: JsonObject,
        version: Int,
        targetClientId: String?,
        deadline: RequestDeadline,
    ): CodexIpcAnswer.Lost? {
        if (closed.get() || !socket.isOpen()) return CodexIpcAnswer.Lost(CLOSED_REASON, sent = false)
        val writeTimeoutMs = remainingTimeoutMs(deadline.deadlineNanos)
        if (writeTimeoutMs == 0L) return CodexIpcAnswer.Lost(REQUEST_TIMEOUT_REASON, sent = false)
        val envelope = CodexIpcEnvelopes.request(
            waiting,
            RequestDetails(clientId, params, version, deadline.timeoutMs, targetClientId),
        )
        var attemptedWrite = false
        return try {
            val written = withTimeoutOrNull(writeTimeoutMs) {
                attemptedWrite = true
                runInterruptible(io) { socket.send(JsonWire.string(envelope)) }
                true
            }
            if (written == null) {
                finish(REQUEST_TIMEOUT_REASON)
                CodexIpcAnswer.Lost(REQUEST_TIMEOUT_REASON, sent = attemptedWrite)
            } else {
                null
            }
        } catch (cancelled: CancellationException) {
            finish(CLOSED_REASON)
            throw cancelled
        } catch (_: CodexIpcFrameTooLargeException) {
            CodexIpcAnswer.Lost("the desktop IPC request exceeded its outbound frame limit", sent = false)
        } catch (failure: IOException) {
            finish(CLOSED_REASON)
            CodexIpcAnswer.Lost(
                "the desktop Codex IPC request could not be written: ${SafeFailureText.render(failure)}",
                sent = true,
            )
        }
    }

    /** Sends a desktop broadcast; null means the full frame was written, otherwise it was not confirmed sent. */
    suspend fun broadcast(
        method: String,
        params: JsonObject,
        version: Int,
        targetClientIds: List<String>? = null,
    ): String? {
        if (closed.get() || !socket.isOpen()) return CLOSED_REASON
        val envelope = buildJsonObject {
            put(FIELD_TYPE, TYPE_BROADCAST)
            put(FIELD_SOURCE_CLIENT_ID, clientId)
            put(FIELD_METHOD, method)
            put(FIELD_PARAMS, params)
            put(FIELD_VERSION, version)
            targetClientIds?.let { targets ->
                put(FIELD_TARGET_CLIENT_IDS, JsonArray(targets.map { JsonPrimitive(it) }))
            }
        }
        return try {
            val written = withTimeoutOrNull(BROADCAST_WRITE_TIMEOUT_MS) {
                runInterruptible(io) { socket.send(JsonWire.string(envelope)) }
                true
            }
            if (written == null) {
                finish(BROADCAST_TIMEOUT_REASON)
                return BROADCAST_TIMEOUT_REASON
            }
            null
        } catch (cancelled: CancellationException) {
            finish(CLOSED_REASON)
            throw cancelled
        } catch (_: CodexIpcFrameTooLargeException) {
            "the desktop IPC broadcast exceeded its outbound frame limit"
        } catch (_: IOException) {
            finish(CLOSED_REASON)
            CLOSED_REASON
        }
    }

    override fun close() {
        finish(CLOSED_REASON)
    }

    private suspend fun dispatch(text: String) {
        val message = parse(text) ?: throw IOException(INVALID_MESSAGE_REASON)
        when (JsonScalars.str(message, FIELD_TYPE)) {
            TYPE_RESPONSE -> response(message)
            CLIENT_DISCOVERY_REQUEST -> answerDiscovery(message)
            TYPE_REQUEST -> refuse(message)
            TYPE_BROADCAST -> if (targetedHere(message)) broadcasts.send(message)
            else -> Unit
        }
    }

    private fun response(message: JsonObject) {
        val requestId = JsonScalars.str(message, FIELD_REQUEST_ID) ?: return
        val waiting = pending[requestId] ?: return
        CodexIpcEnvelopes.responseAnswer(message, waiting.method)?.let(waiting.answer::complete)
    }

    private suspend fun answerDiscovery(message: JsonObject) {
        val requestId = JsonScalars.str(message, FIELD_REQUEST_ID) ?: return
        send(
            buildJsonObject {
                put(FIELD_TYPE, TYPE_DISCOVERY_RESPONSE)
                put(FIELD_REQUEST_ID, requestId)
                put("response", buildJsonObject { put("canHandle", false) })
            },
        )
    }

    private suspend fun refuse(message: JsonObject) {
        val requestId = JsonScalars.str(message, FIELD_REQUEST_ID) ?: return
        val method = JsonScalars.str(message, FIELD_METHOD)
        send(
            buildJsonObject {
                put(FIELD_TYPE, TYPE_RESPONSE)
                put(FIELD_REQUEST_ID, requestId)
                if (method != null) put(FIELD_METHOD, method)
                put(FIELD_RESULT_TYPE, FIELD_ERROR)
                put(FIELD_ERROR, IPC_NO_HANDLER)
            },
        )
    }

    private suspend fun send(message: JsonObject) {
        try {
            runInterruptible(io) { socket.send(JsonWire.string(message)) }
        } catch (cancelled: CancellationException) {
            finish(CLOSED_REASON)
            throw cancelled
        } catch (_: CodexIpcFrameTooLargeException) {
            finish(CLOSED_REASON)
        } catch (_: IOException) {
            finish(CLOSED_REASON)
        }
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text).jsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun targetedHere(message: JsonObject): Boolean {
        val targets = message[FIELD_TARGET_CLIENT_IDS] as? JsonArray ?: return true
        return targets.any { JsonScalars.str(it) == clientId }
    }

    private fun remainingTimeoutMs(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))

    private fun finish(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (_: IOException) {
            // why: still settle readers and pending requests if the operating system rejects close().
        } finally {
            broadcasts.close()
            pending.values.forEach { it.answer.complete(CodexIpcAnswer.Lost(reason, sent = true)) }
            pending.clear()
        }
    }
}

private data class Pending(
    val requestId: String,
    val method: String,
    val answer: CompletableDeferred<CodexIpcAnswer>,
)

private data class RequestDeadline(val timeoutMs: Long, val deadlineNanos: Long)

private data class RequestDetails(
    val sourceClientId: String,
    val params: JsonObject,
    val version: Int,
    val timeoutMs: Long,
    val targetClientId: String?,
)

private object CodexIpcEnvelopes {
    fun request(waiting: Pending, details: RequestDetails): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_REQUEST)
        put(FIELD_REQUEST_ID, waiting.requestId)
        put(FIELD_SOURCE_CLIENT_ID, details.sourceClientId)
        put(FIELD_VERSION, details.version)
        put(FIELD_METHOD, waiting.method)
        put(FIELD_PARAMS, details.params)
        details.targetClientId?.let { put(FIELD_TARGET_CLIENT_ID, it) }
        put(FIELD_TIMEOUT_MS, details.timeoutMs)
    }

    fun responseAnswer(message: JsonObject, expectedMethod: String): CodexIpcAnswer? {
        val responseMethod = JsonScalars.str(message, FIELD_METHOD)
        return when (JsonScalars.str(message, FIELD_RESULT_TYPE)) {
            RESULT_SUCCESS -> if (responseMethod == expectedMethod) CodexIpcAnswer.Result(message) else null
            FIELD_ERROR -> if (responseMethod == null || responseMethod == expectedMethod) {
                CodexIpcAnswer.Error(JsonScalars.str(message, FIELD_ERROR) ?: REQUEST_REFUSED_REASON)
            } else {
                null
            }
            else -> CodexIpcAnswer.Error(RESPONSE_RESULT_TYPE_REASON)
        }
    }
}
