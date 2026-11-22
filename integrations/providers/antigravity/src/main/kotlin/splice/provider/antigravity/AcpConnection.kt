// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md). One ACP agent process, spoken to over its standard
// input and output: newline-delimited JSON-RPC 2.0, as the Agent Client Protocol (v1) has it. A request waits for its
// answer; everything else the agent writes (session/update notifications and the agent's own requests) arrives on
// [AcpConnection.incoming] in the order it was written. Closing it ends the agent and every process it started.
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
import kotlinx.serialization.json.putJsonObject
import splice.core.process.ProcessTree
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.SafeFailureText
import java.io.BufferedReader
import java.io.File
import java.io.IOException
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

    /** The agent ended, or its input closed, before it answered. */
    data object Ended : AcpAnswer()
}

/** The agent started from [command] in [workDir], its standard error written to [errors], its output read on [io] in
 *  [scope]. Starting it throws the IOException of a command that can't be run. */
internal class AcpConnection(
    command: List<String>,
    workDir: Path,
    private val errors: File,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
) : AutoCloseable {
    private val process: Process = ProcessBuilder(command)
        .directory(workDir.toFile())
        .redirectError(errors)
        .start()
    private val input: OutputStream = process.outputStream
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<AcpAnswer>>()
    private val ids = AtomicLong(0)
    private val messages = Channel<JsonObject>(Channel.UNLIMITED)

    @Volatile
    private var ended = false

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
        try {
            return if (ended || !sent(message(id, method, params))) AcpAnswer.Ended else answer.await()
        } finally {
            pending.remove(id)
        }
    }

    /** [method] with [params], its answer left to arrive on [incoming] in the order the agent wrote it, after every
     *  update it sent before; the request's id, or null once the agent's input has closed. */
    fun post(method: String, params: JsonObject): Long? {
        val id = ids.incrementAndGet()
        return id.takeIf { !ended && sent(message(id, method, params)) }
    }

    /** The notification [method] with [params]; false once the agent's input has closed. */
    fun notify(method: String, params: JsonObject): Boolean = sent(
        buildJsonObject {
            put(ACP_JSONRPC_KEY, "2.0")
            put("method", method)
            put("params", params)
        },
    )

    /** [result] as the answer to the agent's own request [id]; false once the agent's input has closed. */
    fun respond(id: JsonElement, result: JsonObject): Boolean = sent(
        buildJsonObject {
            put(ACP_JSONRPC_KEY, "2.0")
            put("id", id)
            put("result", result)
        },
    )

    /** JSON-RPC error [code] with [message] as the answer to the agent's own request [id]. */
    fun refuse(id: JsonElement, code: Int, message: String): Boolean = sent(
        buildJsonObject {
            put(ACP_JSONRPC_KEY, "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        },
    )

    /** The first line the agent wrote that was no JSON-RPC message, cut short; null while every line has been one. */
    @Volatile
    var unreadable: String? = null
        private set

    /** The last line the agent wrote to its standard error, to say why it failed; empty when it wrote none. */
    fun lastWords(): String {
        val lines = try {
            errors.readLines()
        } catch (unread: IOException) {
            listOf("its standard error could not be read (${SafeFailureText.render(unread)})")
        }
        val last = lines.lastOrNull { it.isNotBlank() } ?: return ""
        return " (agy said: ${last.take(LAST_WORDS_CHARS)})"
    }

    /** Ends the agent and every process it started (ProcessTree). */
    override fun close() {
        ProcessTree(process).kill()
    }

    private fun message(id: Long, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put(ACP_JSONRPC_KEY, "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }

    /** Whether [message] reached the agent's input: false once it has closed. */
    private fun sent(message: JsonObject): Boolean {
        val line = (JsonWire.string(message) + "\n").toByteArray(StandardCharsets.UTF_8)
        return try {
            synchronized(input) {
                input.write(line)
                input.flush()
            }
            true
        } catch (expectedOnceClosed: IOException) {
            false
        }
    }

    private fun readAll() {
        try {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    JsonScalars.objectOrNull(Json, line)?.let(::take) ?: noted(line)
                }
            }
        } finally {
            ended = true
            pending.values.forEach { it.complete(AcpAnswer.Ended) }
            pending.clear()
            messages.close()
        }
    }

    private fun noted(line: String) {
        if (unreadable == null) unreadable = line.take(UNREADABLE_CHARS)
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

/** How much of the agent's last line of standard error a failure quotes. */
private const val LAST_WORDS_CHARS = 300

/** How much of a line that was no JSON-RPC message is kept to say what it was. */
private const val UNREADABLE_CHARS = 300

/** The member every JSON-RPC 2.0 message carries, with its version. */
private const val ACP_JSONRPC_KEY = "jsonrpc"
