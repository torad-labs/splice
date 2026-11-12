package splice.control.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.HttpStatus

/** Management operations never cancel, replay or forget a request already sent to the child. */
public class McpReplacements internal constructor(private val owned: HostedReplacements) {
    /** Reports host admissions separately from unsettled child request IDs. */
    public fun status(name: String): McpReply =
        owned.status(name)?.let { McpReply(HttpStatus.OK, it.toString()) }
            ?: error(HttpStatus.NOT_FOUND, "MCP server is not running")

    /** New calls wait before forwarding; cancellation of already-admitted calls remains available. */
    public fun drain(name: String): McpReply =
        if (owned.pause(name)) status(name) else error(HttpStatus.NOT_FOUND, "MCP server is not running")

    /** Cancels a drain only while no replacement handshake or commit is in progress. */
    public fun resume(name: String): McpReply =
        if (owned.resume(name)) {
            status(name)
        } else {
            error(HttpStatus.SERVICE_UNAVAILABLE, "MCP replacement is in progress")
        }

    /**
     * Starts the exact tuple now configured for every alias, checks the live sessions' protocol and
     * capabilities, then retires the settled child. Process-private backend state is not transferred.
     * A refused attempt leaves the old child and client sessions intact, with admissions still paused.
     */
    public suspend fun replace(name: String): McpReply = when (val result = owned.replace(name)) {
        is McpResult.Served -> status(name)
        is McpResult.Refused -> error(HttpStatus.SERVICE_UNAVAILABLE, result.reason)
    }

    private fun error(status: Int, message: String): McpReply =
        McpReply(status, buildJsonObject { put("error", message) }.toString())
}
