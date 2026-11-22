// NEW: modes-mcp (Nov 21, 2026) — an MCP server the daemon answers itself. Every hosted server until now is a child
// process a person declared in their own Claude Code config. A mode whose kind is mcp (modes/README.md) is code inside
// splice: its server has no process to start, no listing to cache and no crash to back off from. This is the port the
// host asks, and it knows nothing of modes: :integrations-mcp may not see :modes-api (gradle/module-law.txt), so the
// app supplies the servers.
package splice.control.mcp.inprocess

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One MCP server answered in process: what a client is told of its tools and resources, and its answers. Every value
 *  is the MCP wire shape as the client reads it, so what a server puts in a `_meta` reaches the client untouched. */
public interface InProcessMcpServer {
    /** `tools/list`'s `tools`: each `{name, description, inputSchema}`, with its `_meta` when it has one. */
    public fun tools(): JsonArray

    /** `tools/call`'s result for [tool] called with [arguments]: `{content, isError}`. [session] is the client's MCP
     *  session id, the only name the transport has for who is calling. */
    public fun call(tool: String, arguments: JsonObject, session: String?): JsonObject

    /** `resources/list`'s `resources`: each `{uri, name, mimeType}`, with its `_meta` when it has one. */
    public fun resources(): JsonArray

    /** `resources/read`'s `contents` for [uri], or null when the server has no such resource. */
    public fun read(uri: String): JsonArray?

    /** What the server tells a client of how to use it, which `initialize` gives as its `instructions`; null says
     *  nothing. */
    public fun instructions(): String? = null
}

/** The servers this daemon answers itself. [server] is the one called [name] for a client on [head] (the head its
 *  launch entry names, null for a client that named none), or null when there is none for that client: the name is
 *  then a hosted server's, or nobody's, and the host answers as it always has. */
public fun interface InProcessMcp {
    public fun server(name: String, head: String?): InProcessMcpServer?
}
