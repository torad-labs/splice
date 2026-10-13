// NEW: MCP hub step 1 (docs/specs/mcp-hub.md, draft 2, which Marcos approved on Oct 12) — the servers Claude Code
// itself would load for one launch folder, and the ones it would leave out with the reason. Read-only: the plan
// reads his files and changes no launch. McpLaunchPlanner builds it; /api/mcp reports one per launch folder.
package splice.client.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/** What Claude Code assumes when an entry names no transport. */
private const val PLAN_DEFAULT_TRANSPORT = "stdio"

/** Where a server was declared. Claude Code's own precedence for one name is local, then project, then user;
 *  a plugin's servers carry their own names and lose to any of those that runs the same server. */
public enum class McpPlanScope(public val wire: String) {
    LOCAL("local"),
    PROJECT("project"),
    USER("user"),
    PLUGIN("plugin"),
}

/** One server Claude Code loads for the folder, under the name its tools carry (`mcp__<name>__<tool>`). [entry]
 *  is the declaration as Claude Code reads it, a plugin's root already filled in. It can hold credentials, so
 *  [McpLaunchPlan.json] never prints it. */
public data class McpPlannedServer(
    val name: String,
    val scope: McpPlanScope,
    val source: Path,
    val entry: JsonObject,
)

/** A declared server Claude Code would not load in the folder, with the reason in words a person can act on. */
public data class McpLeftOutServer(val name: String, val scope: McpPlanScope, val source: Path, val reason: String)

/** The plan for one launch: the [folder] Claude Code starts in, under the config home [configDir]. */
public data class McpLaunchPlan(
    val folder: Path,
    val configDir: Path,
    val servers: List<McpPlannedServer>,
    val leftOut: List<McpLeftOutServer>,
) {
    /** Names, scopes, files and transports only: an entry's command, arguments, headers and environment stay out. */
    public fun json(): JsonObject = buildJsonObject {
        put("folder", folder.toString())
        put("config_dir", configDir.toString())
        put(
            "servers",
            buildJsonArray {
                servers.forEach { server ->
                    addJsonObject {
                        put("name", server.name)
                        put("scope", server.scope.wire)
                        put("source", server.source.toString())
                        put("transport", (server.entry["type"] as? JsonPrimitive)?.content ?: PLAN_DEFAULT_TRANSPORT)
                    }
                }
            },
        )
        put(
            "left_out",
            buildJsonArray {
                leftOut.forEach { server ->
                    addJsonObject {
                        put("name", server.name)
                        put("scope", server.scope.wire)
                        put("source", server.source.toString())
                        put("reason", server.reason)
                    }
                }
            },
        )
    }
}
