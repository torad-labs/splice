// NEW: desktop-3p-parity (Nov 23, 2026). The stdio MCP bridge (`splice mcp stdio <name>`): Claude Desktop starts only
// stdio servers from `claude_desktop_config.json`, so for a server Splice hosts, or one the person reaches at a URL,
// Desktop starts this instead. Each line it reads on stdin is one JSON-RPC message, handed to the server; each message
// the server sends, in answer or on its own, is written to stdout as one line. Requests run side by side, as a client
// sends them, and everything else goes in order. A request the server can't take is answered with a JSON-RPC error
// that says why, so the client never waits on a request nobody will answer; nothing it writes carries a header value.
package splice.app.mcp.stdio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.app.WireJson
import splice.core.terminal.TerminalOutput
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream

internal class McpStdioBridge(
    /** The server's name, as the client's errors and the bridge's own lines on stderr name it. */
    private val name: String,
    private val transport: McpStdioTransport,
    private val io: CoroutineDispatcher,
    private val err: TerminalOutput,
) {
    private val replies = McpStdioReplies()

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
        val listening = McpStdioListening(this)
        try {
            val refused = opened(out)
            refused?.let { err.line("splice mcp stdio: the MCP server '$name' did not open: $it") }
            if (refused == null) {
                listen(listening, out)
                read(input, out, listening)
            }
            refused == null
        } finally {
            // However the bridge ends, cancelled included, the session is ended and every stream stopped.
            withContext(NonCancellable) {
                reached("end its session") { transport.close() }
                listening.stop()
                lines.close()
                writer.join()
            }
        }
    }

    /** Why the server did not open, in words safe to print, or null when it did. */
    private suspend fun opened(out: TerminalOutput): String? = try {
        transport.open(out)
    } catch (failure: IOException) {
        "it could not be reached (${SafeFailureText.render(failure)})"
    } catch (failure: IllegalArgumentException) {
        "a request to it could not be made (${SafeFailureText.render(failure)})"
    }

    /** Every line of [input] handed on, until it ends; requests launched side by side, the rest in order. */
    private suspend fun read(
        input: BufferedReader,
        out: TerminalOutput,
        listening: McpStdioListening,
    ) = supervisorScope {
        var line = withContext(io) { input.readLine() }
        while (line != null) {
            if (line.isNotBlank()) handle(line, out, this, listening)
            line = withContext(io) { input.readLine() }
        }
    }

    private suspend fun handle(line: String, out: TerminalOutput, scope: CoroutineScope, listening: McpStdioListening) {
        val message = WireJson.fields(line, STDIO_ROUTING_FIELDS)
        if (message == null) {
            out.line(replies.error(JsonNull, STDIO_PARSE_ERROR, "the line is not a JSON-RPC message"))
            return
        }
        val method = JsonScalars.str(message, STDIO_METHOD)
        when {
            method == STDIO_INITIALIZE -> {
                deliver(message, line, out)
                listen(listening, out)
            }
            method != null && STDIO_ID in message -> scope.launch { deliver(message, line, out) }
            else -> deliver(message, line, out)
        }
    }

    /** What the server sends on its own, heard under [listening] so the bridge can stop hearing it when it ends. */
    private fun listen(listening: McpStdioListening, out: TerminalOutput) {
        listening.start { reached("keep its stream open") { transport.listen(out) } }
    }

    /** [message] handed to the server; a request it can't take answered with an error naming why. */
    private suspend fun deliver(message: JsonObject, wire: String, out: TerminalOutput) {
        val request = message[STDIO_ID]?.takeIf { STDIO_METHOD in message }
        val why = try {
            when (val sent = transport.send(message, wire, out)) {
                McpStdioSent.Delivered -> null
                is McpStdioSent.Refused -> "the MCP server '$name' answered HTTP ${sent.status}"
                McpStdioSent.Unanswered -> "the MCP server '$name' took the request without answering it"
            }
        } catch (failure: IOException) {
            "Splice could not reach the MCP server '$name' (${SafeFailureText.render(failure)})"
        } catch (failure: IllegalArgumentException) {
            "Splice could not make the request to '$name' (${SafeFailureText.render(failure)})"
        }
        if (why == null) return
        err.line("splice mcp stdio: $why")
        request?.let { out.line(replies.error(it, STDIO_SERVER_ERROR, why)) }
    }

    /** [step] run, a failure to reach the server said on stderr; whether it went through. */
    private inline fun reached(what: String, step: () -> Unit): Boolean {
        val failure = try {
            step()
            null
        } catch (failed: IOException) {
            failed
        } catch (failed: IllegalArgumentException) {
            failed
        }
        failure?.let { err.line("splice mcp stdio: could not $what for '$name' (${SafeFailureText.render(it)})") }
        return failure == null
    }
}

private val STDIO_ROUTING_FIELDS = setOf(STDIO_METHOD, STDIO_ID)

// JSON-RPC's code for a line that is not JSON.
private const val STDIO_PARSE_ERROR = -32_700

// The start of JSON-RPC's range for errors a server defines: here, the server could not be reached or refused.
private const val STDIO_SERVER_ERROR = -32_000
