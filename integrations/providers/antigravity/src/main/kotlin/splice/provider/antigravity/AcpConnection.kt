// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md). One ACP agent process, spoken to over its standard
// input and output: newline-delimited JSON-RPC 2.0, as the Agent Client Protocol (v1) has it. A request waits for its
// answer; everything else the agent writes (session/update notifications and the agent's own requests) arrives on
// [AcpConnection.incoming] in the order it was written.
package splice.provider.antigravity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the agent answered a request with. */
internal sealed class AcpAnswer {
    /** Its result. */
    data class Result(val result: JsonObject) : AcpAnswer()

    /** Its error, as the agent gave it: `-32000` is ACP's "authentication required". */
    data class Refused(val code: Int, val message: String) : AcpAnswer()

    /** The agent ended before it answered. */
    data object Ended : AcpAnswer()
}

/** The agent started from [command] in [workDir], its output read on [io] in [scope]. Its standard error is not
 *  read. Close it when done; closing ends the process. */
internal class AcpConnection(command: List<String>, workDir: Path, scope: CoroutineScope, io: CoroutineDispatcher) :
    AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val process: Process = ProcessBuilder(command)
        .directory(workDir.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    private val input: OutputStream = process.outputStream
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<AcpAnswer>>()
    private val ids = AtomicLong(0)
    private val messages = Channel<JsonObject>(Channel.UNLIMITED)

    /** The agent's notifications and requests, in order. Closed when the agent's output ends. */
    val incoming: ReceiveChannel<JsonObject> get() = messages

    init {
        scope.launch(io) { readAll() }
    }

    /** [method] with [params], and what the agent answered. */
    suspend fun request(method: String, params: JsonObject): AcpAnswer {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<AcpAnswer>()
        pending[id] = answer
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        return answer.await()
    }

    /** [method] with [params], expecting no answer. */
    fun notify(method: String, params: JsonObject) {
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
        )
    }

    /** The answer to the agent's own request [id]. */
    fun respond(id: JsonElement, result: JsonObject) {
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )
    }

    override fun close() {
        process.destroy()
    }

    private fun write(message: JsonObject) {
        val line = (JsonWire.string(message) + "\n").toByteArray(StandardCharsets.UTF_8)
        synchronized(input) {
            input.write(line)
            input.flush()
        }
    }

    private fun readAll() {
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
            lines.mapNotNull { JsonScalars.objectOrNull(json, it) }.forEach(::take)
        }
        pending.values.forEach { it.complete(AcpAnswer.Ended) }
        pending.clear()
        messages.close()
    }

    private fun take(message: JsonObject) {
        val answer = JsonScalars.long(message, "id")?.takeIf { "method" !in message }?.let { pending.remove(it) }
        if (answer == null) {
            messages.trySend(message)
            return
        }
        val error = message["error"] as? JsonObject
        answer.complete(
            if (error == null) {
                AcpAnswer.Result(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
            } else {
                AcpAnswer.Refused(JsonScalars.int(error, "code") ?: 0, JsonScalars.str(error, "message").orEmpty())
            },
        )
    }
}
