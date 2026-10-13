// NEW: MCP hub step 1 (docs/specs/mcp-hub.md) — whether a repo `.mcp.json` server is approved, rejected or still
// pending in one folder, decided the way Claude Code 2.1.296 decides it (its bundle's wtt, x8t and Z8t): an entry
// in any source's `disabledMcpjsonServers` rejects it; otherwise `enableAllProjectMcpServers` or an entry in
// `enabledMcpjsonServers` approves it; otherwise it waits for the approval prompt. The planner hands in only the
// sources Claude Code would read: the folder's checked-in settings count once the folder is trusted, the way a
// cloned repo cannot approve its own servers. A pending server stays out of the plan, because the strict launch
// skips the prompt that would have asked (proof b).
package splice.client.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val ENABLE_ALL_REPO_SERVERS = "enableAllProjectMcpServers"
private const val ENABLED_REPO_SERVERS = "enabledMcpjsonServers"
private const val DISABLED_REPO_SERVERS = "disabledMcpjsonServers"

/** A repo server's standing in one folder, with the words the startup line gives a server left out. */
internal enum class RepoVerdict(val reason: String?) {
    APPROVED(null),
    PENDING(
        "waiting for approval in this folder: start claude here and approve it, or list it in " +
            "enabledMcpjsonServers in .claude/settings.local.json",
    ),
    REJECTED("rejected by disabledMcpjsonServers in your settings"),
}

/** [sources]: every settings object Claude Code reads approvals from for the folder, already filtered by trust,
 *  the folder's own `.claude.json` record among them (its legacy lists count the same). */
internal class McpRepoApproval(private val sources: List<JsonObject>) {
    fun verdict(name: String): RepoVerdict = when {
        sources.any { name in names(it, DISABLED_REPO_SERVERS) } -> RepoVerdict.REJECTED
        sources.any { approvesAll(it) || name in names(it, ENABLED_REPO_SERVERS) } -> RepoVerdict.APPROVED
        else -> RepoVerdict.PENDING
    }

    private fun approvesAll(source: JsonObject): Boolean =
        (source[ENABLE_ALL_REPO_SERVERS] as? JsonPrimitive)?.booleanOrNull == true

    private fun names(source: JsonObject, key: String): Set<String> =
        (source[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
}
