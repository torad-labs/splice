// NEW: MCP hub item 6 — the hub's three calls and its sweep, as the routes and the host reach them, so a machine the
// hub cannot run on answers each call in words instead of missing the routes (splice-config's hub-or-fallback check,
// Oct 12). McpHub serves them; McpHubOff answers every call 503 with the reason the check gave, and sweeps nothing.
package splice.control.mcp.hub

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus

public interface McpHubCalls {
    /** `POST /api/mcp/hub/resolve`. */
    public fun resolve(body: String): McpHubReply

    /** `POST /api/mcp/hub/start`. */
    public suspend fun start(body: String): McpHubReply

    /** `POST /api/mcp/hub/listing`. */
    public fun listing(body: String): McpHubReply

    /** `POST /api/mcp/hub/roots`: a shared server asked its client for roots. */
    public fun roots(body: String): McpHubReply

    /** The host's idle sweep, at [now], with the MCP idle limit [idleMs]. */
    public fun sweep(now: Long, idleMs: Long)

    /** Each hub-served server, by the name its holder's record writes, to the launch ids (Claude Code pids) of the
     *  sessions the front has bound to its holders, shared and per-session alike. Empty when the front saved nothing
     *  readable (splice-usage's ask for §8's ownership visible per session, Oct 13). */
    public fun attached(): Map<String, Set<String>>
}

/** The hub on a machine it cannot run on: the daemon keeps hosting MCP in process, and the front is told why. */
public class McpHubOff(reason: String) : McpHubCalls {
    private val reply = McpHubReply(
        HttpStatus.SERVICE_UNAVAILABLE,
        JsonWire.string(buildJsonObject { put("error", "the MCP hub is off on this machine: $reason") }),
    )

    override fun resolve(body: String): McpHubReply = reply

    override suspend fun start(body: String): McpHubReply = reply

    override fun listing(body: String): McpHubReply = reply

    override fun roots(body: String): McpHubReply = reply

    override fun sweep(now: Long, idleMs: Long) {
        // Nothing to stop: with the hub off, no holder was started.
    }

    override fun attached(): Map<String, Set<String>> = emptyMap()
}
