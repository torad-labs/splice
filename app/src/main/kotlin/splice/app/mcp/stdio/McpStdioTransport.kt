// NEW: desktop-3p-parity (Nov 23, 2026). What the stdio MCP bridge (`splice mcp stdio`) speaks to: a server at a URL,
// reached over MCP's streamable HTTP or the older HTTP+SSE transport. Claude Desktop starts only stdio servers from
// `claude_desktop_config.json`, so the bridge is the stdio server it starts, and each message it reads on stdin is
// handed to one of these.
package splice.app.mcp.stdio

import kotlinx.serialization.json.JsonObject
import splice.core.terminal.TerminalOutput
import java.net.URI

/** Where a bridged server is: its [url], the [headers] each request carries (the MCP access key for a server Splice
 *  hosts, the person's own for theirs; never printed), and whether it speaks the older HTTP+SSE transport. */
internal data class McpStdioTarget(val url: URI, val headers: Map<String, String>, val legacySse: Boolean = false)

/** What became of one message handed to the server. */
internal sealed class McpStdioSent {
    /** The server took it; whatever it answered went out. */
    data object Delivered : McpStdioSent()

    /** The server answered with HTTP [status], which is not a reply. */
    data class Refused(val status: Int) : McpStdioSent()
}

/** One server the bridge speaks to. Every member may throw IOException when the server can't be reached. */
internal interface McpStdioTransport {
    /** Whatever must exist before the first message: the older transport's event stream and the address it names.
     *  Why it could not, in words safe to print, or null when it could. */
    suspend fun open(out: TerminalOutput): String?

    /** [message] handed to the server, and what it answers to it written to [out]. */
    suspend fun send(message: JsonObject, out: TerminalOutput): McpStdioSent

    /** What the server sends on its own (notifications, its requests), written to [out] until the stream ends. */
    suspend fun listen(out: TerminalOutput)

    /** The session ended and every stream closed. */
    suspend fun close()
}
