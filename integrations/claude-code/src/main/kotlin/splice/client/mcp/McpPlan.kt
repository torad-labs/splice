// NEW: Oct 13, 2026 — what the MCP planner answers, split from McpSharing.kt, which decides it: a server's launch
// tuple, its class, why it is not shared, and one planning pass over the operator's mcpServers. The materializer and
// the hub read these shapes; only McpSharing makes them.
package splice.client.mcp

import kotlinx.serialization.json.JsonObject

/** The launch tuple that IS a hosted server's identity: same tuple, same process. */
public data class McpServerSpec(
    val name: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
    /** The entry's own cwd. Registered services carry an owner-selected absolute directory. */
    val cwd: String? = null,
    val provenance: McpServerProvenance = McpServerProvenance.CLIENT,
)

/** How the hub runs a server (MCP hub step 3): one copy for every session, one copy per session, or not at all,
 *  because the server is the operator's own to start (excluded, unreadable, a web transport, no command). */
public enum class McpShareClass(public val wire: String) {
    SHARED("shared"),
    PER_SESSION("per_session"),
    OWN("own"),
}

/** A stable key for why a server is not shared, and the value the reason names (the variable, the path, the cwd,
 *  the URL). The sentence beside it is what the CLI and doctor print; the console words the key, so no page parses
 *  prose. */
public data class McpWhy(val key: String, val value: String? = null)

/** One planning pass over the operator's `mcpServers`: what is hosted, what is not and why, and
 *  the object to write into a head's `.claude.json` in place of the original. [why] is [passthrough]'s reason as a
 *  key and value, under the same names. */
public data class McpPlan(
    val hosted: Map<String, McpServerSpec>,
    val passthrough: Map<String, String>,
    val rewritten: JsonObject,
    val why: Map<String, McpWhy> = emptyMap(),
    /** The servers the hub starts once per session, each with its declared tuple; also in [passthrough] with its
     *  reason, because until the strict launch (hub step 6) Claude Code starts them itself. */
    val perSession: Map<String, McpServerSpec> = emptyMap(),
) {
    /** [name]'s class: shared, one per session, or the operator's own. */
    public fun classOf(name: String): McpShareClass = when (name) {
        in hosted -> McpShareClass.SHARED
        in perSession -> McpShareClass.PER_SESSION
        else -> McpShareClass.OWN
    }
}
