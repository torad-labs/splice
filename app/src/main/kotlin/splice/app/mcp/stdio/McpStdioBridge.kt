// NEW: desktop-3p-parity (Nov 23, 2026). The stdio MCP bridge (`splice mcp stdio <name>`): Claude Desktop starts only
// stdio servers from `claude_desktop_config.json`, so for a server Splice hosts, or one the person reaches at a URL,
// Desktop starts this instead. Each line it reads on stdin is one JSON-RPC message, handed to the server; each message
// the server sends, in answer or on its own, is written to stdout as one line. Requests run side by side, as a client
// sends them, and everything else goes in order. A request the server can't take is answered with a JSON-RPC error
// that says why, so the client never waits on a request nobody will answer; nothing it writes carries a header value.
package splice.app.mcp.stdio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.SafeFailureText
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

internal class McpStdioBridge(
    /** The server's name, as the client's errors and the bridge's own lines on stderr name it. */
    private val name: String,
    private val transport: McpStdioTransport,
    private val io: CoroutineDispatcher,
    private val err: TerminalOutput,
) {
    /** Bridges [input] to the server and the server to [output] until [input] ends; false when the server could not
     *  be reached at all, which a client sees as a server that failed to start. */
    suspend fun run(input: BufferedReader, output: OutputStream): Boolean = coroutineScope {
        val lines = Channel<String>(Channel.UNLIMITED)
        val out = TerminalOutput { lines.trySend(it) }
        val writer = launch(io) {
            for (line in lines) {
                output.write((line + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
        val listening = Listening(this)
        val refused = try {
            transport.open(out)
        } catch (failure: IOException) {
            "it could not be reached (${SafeFailureText.render(failure)})"
        }
        refused?.let { err.line("splice mcp stdio: the MCP server '$name' did not open: $it") }
        val opened = refused == null
        if (opened) {
            listen(listening, out)
            read(input, out, listening)
        }
        reached("end its session") { transport.close() }
        listening.stop()
        lines.close()
        writer.join()
        opened
    }

    /** Every line of [input] handed on, until it ends; requests launched side by side, the rest in order. */
    private suspend fun read(input: BufferedReader, out: TerminalOutput, listening: Listening) = supervisorScope {
        var line = withContext(io) { input.readLine() }
        while (line != null) {
            if (line.isNotBlank()) handle(line, out, this, listening)
            line = withContext(io) { input.readLine() }
        }
    }

    private suspend fun handle(line: String, out: TerminalOutput, scope: CoroutineScope, listening: Listening) {
        val message = JsonScalars.objectOrNull(Json, line)
        if (message == null) {
            out.line(failure(JsonNull, STDIO_PARSE_ERROR, "the line is not a JSON-RPC message"))
            return
        }
        val method = JsonScalars.str(message, STDIO_METHOD)
        when {
            method == STDIO_INITIALIZE -> {
                deliver(message, out)
                listen(listening, out)
            }
            method != null && STDIO_ID in message -> scope.launch { deliver(message, out) }
            else -> deliver(message, out)
        }
    }

    /** What the server sends on its own, heard under [listening] so the bridge can stop hearing it when it ends. */
    private fun listen(listening: Listening, out: TerminalOutput) {
        listening.start { reached("keep its stream open") { transport.listen(out) } }
    }

    /** [message] handed to the server; a request it can't take answered with an error naming why. */
    private suspend fun deliver(message: JsonObject, out: TerminalOutput) {
        val request = message[STDIO_ID]?.takeIf { STDIO_METHOD in message }
        val why = try {
            when (val sent = transport.send(message, out)) {
                McpStdioSent.Delivered -> null
                is McpStdioSent.Refused -> "the MCP server '$name' answered HTTP ${sent.status}"
            }
        } catch (failure: IOException) {
            "Splice could not reach the MCP server '$name' (${SafeFailureText.render(failure)})"
        }
        if (why == null) return
        err.line("splice mcp stdio: $why")
        request?.let { out.line(failure(it, STDIO_SERVER_ERROR, why)) }
    }

    /** [step] run, a failure to reach the server said on stderr; whether it went through. */
    private inline fun reached(what: String, step: () -> Unit): Boolean = try {
        step()
        true
    } catch (failure: IOException) {
        err.line("splice mcp stdio: could not $what for '$name' (${SafeFailureText.render(failure)})")
        false
    }

    private fun failure(id: JsonElement, code: Int, text: String): String = JsonWire.string(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put(STDIO_ID, id)
            putJsonObject("error") {
                put("code", code)
                put("message", text)
            }
        },
    )
}

/** What the bridge hears from the server on its own, launched in the bridge's [scope] so reading stdin never waits
 *  on it, and stopped when stdin ends. */
private class Listening(private val scope: CoroutineScope) {
    private val jobs = ConcurrentLinkedQueue<Job>()

    inline fun start(crossinline step: suspend () -> Unit) {
        jobs += scope.launch { step() }
    }

    suspend fun stop() {
        jobs.forEach { it.cancelAndJoin() }
    }
}

// JSON-RPC's code for a line that is not JSON.
private const val STDIO_PARSE_ERROR = -32_700

// The start of JSON-RPC's range for errors a server defines: here, the server could not be reached or refused.
private const val STDIO_SERVER_ERROR = -32_000
