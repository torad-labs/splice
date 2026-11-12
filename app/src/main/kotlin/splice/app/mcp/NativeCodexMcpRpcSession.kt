package splice.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.LogSink
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

private const val CODEX_RPC_PROTOCOL_REASON = "Codex app-server returned an invalid config API response."
private const val CODEX_RPC_TIMEOUT_REASON = "Codex app-server did not answer within the allowed time."
private const val CODEX_RPC_INITIALIZE_REASON = "Codex app-server could not initialize the config API."

// why: config reads, optimistic writes and MCP reloads share a bounded dialog; a blocked child is terminated on expiry.
private const val CODEX_RPC_DIALOG_TIMEOUT_MS = 15_000L

/** An owned transport's serialized JSON-RPC dialog, bounded independently of blocking process pipes. */
internal class NativeCodexMcpRpcSession(
    private val process: Process,
    private val ioDispatcher: CoroutineDispatcher,
    private val log: LogSink,
) {
    private val json: Json = Json
    private val reader: BufferedReader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
    private val writer: BufferedWriter = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
    private val ownedProcess = NativeCodexMcpOwnedProcess(log)
    private val closed = AtomicBoolean(false)
    private var nextId = 1L

    suspend fun initialize(timeoutMs: Long) {
        val response = request(
            "initialize",
            buildJsonObject {
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "splice-mcp")
                        put("version", "0.4.0")
                    },
                )
                put("capabilities", buildJsonObject { put("experimentalApi", true) })
            },
            timeoutMs,
        )
        if (response !is JsonObject) fail(CODEX_RPC_INITIALIZE_REASON)
        notification("initialized", timeoutMs)
    }

    suspend fun request(
        method: String,
        params: JsonObject? = null,
        timeoutMs: Long = CODEX_RPC_DIALOG_TIMEOUT_MS,
    ): JsonElement {
        val id = JsonPrimitive(nextId++)
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        return dialog(
            timeoutMs,
            NativeCodexMcpDialog {
                send(message)
                receive(id)
            },
        )
    }

    private suspend fun notification(method: String, timeoutMs: Long) {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        dialog(timeoutMs, NativeCodexMcpDialog { send(message) })
    }

    private suspend fun send(message: JsonObject) {
        blockingIo {
            writer.write(message.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private suspend fun receive(expectedId: JsonPrimitive): JsonElement {
        var response = readResponse(expectedId)
        while (response == null) response = readResponse(expectedId)
        return response
    }

    private suspend fun readResponse(expectedId: JsonPrimitive): JsonElement? {
        val message = readMessage()
        return if (message["id"] == expectedId) responseResult(message) else null
    }

    private suspend fun readMessage(): JsonObject {
        val line = blockingIo { reader.readLine() } ?: fail(CODEX_RPC_PROTOCOL_REASON)
        return try {
            json.parseToJsonElement(line) as? JsonObject ?: fail(CODEX_RPC_PROTOCOL_REASON)
        } catch (_: SerializationException) {
            fail(CODEX_RPC_PROTOCOL_REASON)
        }
    }

    private fun responseResult(message: JsonObject): JsonElement {
        if (message["error"] != null && message["error"] != JsonNull) fail(CODEX_RPC_PROTOCOL_REASON)
        return message["result"] ?: fail(CODEX_RPC_PROTOCOL_REASON)
    }

    private suspend fun <T> blockingIo(operation: NativeCodexMcpBlockingOperation<T>): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                ownedProcess.terminate(process)
            }
            val worker = Runnable {
                try {
                    continuation.resumeWith(Result.success(operation()))
                } catch (failure: IOException) {
                    continuation.resumeWith(Result.failure(failure))
                } catch (_: SecurityException) {
                    continuation.resumeWith(Result.failure(NativeCodexMcpRpcFailure(CODEX_RPC_PROTOCOL_REASON)))
                }
            }
            try {
                ioDispatcher.dispatch(continuation.context, worker)
            } catch (_: RejectedExecutionException) {
                continuation.resumeWith(Result.failure(NativeCodexMcpRpcFailure(CODEX_RPC_PROTOCOL_REASON)))
            }
        }

    private suspend fun <T> dialog(timeoutMs: Long, operation: NativeCodexMcpDialog<T>): T =
        try {
            withTimeout(timeoutMs) { operation() }
        } catch (timeout: TimeoutCancellationException) {
            close()
            currentCoroutineContext().ensureActive()
            throw NativeCodexMcpRpcFailure(CODEX_RPC_TIMEOUT_REASON).also { it.initCause(timeout) }
        } catch (cancelled: CancellationException) {
            close()
            rethrow(cancelled)
        }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable + ioDispatcher) {
            ownedProcess.close(process)
        }
    }

    private fun rethrow(cancelled: CancellationException): Nothing = throw cancelled

    private fun fail(reason: String): Nothing = throw NativeCodexMcpRpcFailure(reason)
}
