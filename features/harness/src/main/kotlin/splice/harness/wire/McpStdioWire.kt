// NEW: the harness (docs/specs/harness.md, decision 13). A server the session's folder declares with a command: splice
// starts it in that folder for the call, speaks to it one JSON-RPC message a line, and stops it, and what it started
// under it, when the call ends.
package splice.harness.wire

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import splice.core.parse.lenientJson
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Starts [command] in [folder], its environment the daemon's with [env] over it; throws [IOException] when it cannot
 *  be started. Its stderr is not read: a server's own words may hold anything. */
internal class McpStdioWire(
    command: List<String>,
    env: Map<String, String>,
    folder: Path,
    private val io: CoroutineDispatcher,
) : McpWire {
    private val answers = McpAnswers()
    private val incoming = Channel<JsonObject>(Channel.UNLIMITED)
    private val process: Process = ProcessBuilder(command)
        .directory(folder.toFile())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .also { it.environment().putAll(env) }
        .start()
    private val stdin = process.outputStream

    init {
        Executors.defaultThreadFactory().newThread(::pump).apply {
            isDaemon = true
            name = "harness-mcp-stdout"
        }.start()
    }

    /** Reads the server's lines until it ends; a line that is not a JSON object is not a message. */
    private fun pump() {
        try {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                JsonScalars.objectOrNull(lenientJson, line.trim())?.let { incoming.trySend(it) }
            }
        } catch (_: IOException) {
            // the process is gone: nothing more arrives
        } finally {
            incoming.close()
        }
    }

    override suspend fun post(message: JsonObject) {
        try {
            withContext(io) {
                stdin.write(JsonWire.bytes(message))
                stdin.write(LINE_END)
                stdin.flush()
            }
        } catch (_: IOException) {
            throw McpWireFailure("it stopped listening")
        }
    }

    override suspend fun ask(request: JsonObject, heard: McpHeard): JsonObject? {
        post(request)
        val id = request["id"]
        while (true) {
            val message = incoming.receiveCatching().getOrNull() ?: return null
            if (answers.to(id, message)) return message
            heard.heard(message)
        }
    }

    /** Closes the server's input, then stops it and everything it started: the server is often a launcher (`npx`,
     *  `uvx`) over the program. */
    override fun close() {
        incoming.close()
        try {
            stdin.close()
        } catch (_: IOException) {
            // already closed by the server's exit
        }
        val started = process.descendants().toList()
        // a server that ends on its input closing, as most do, has been told it is cancelled and gets to end by itself
        if (!process.waitFor(SERVER_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        started.forEach { it.destroyForcibly() }
    }
}

private val LINE_END = "\n".toByteArray()

/** why: long enough for a server to read a last line and leave, short enough that one that never leaves is not
 *  waited for. */
private const val SERVER_EXIT_GRACE_MS = 300L
