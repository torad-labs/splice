// NEW (v0.4.0, FEATURES.md §8): decides which of the operator's MCP servers splice may host ONCE
// for every session, and rewrites their entries so each head's Claude Code connects to the host
// over HTTP instead of spawning its own copy. Framework-free on purpose: the host itself lives in
// :control; this planner is the single source of truth both the materializer (what to rewrite)
// and the host (what to spawn) read, so the two can never disagree about a server's identity.
//
// Eligibility is deliberately conservative — a server that is NOT rewritten keeps today's
// behaviour exactly (one process per session), which is the never-below-status-quo floor:
//   - only stdio entries (absent `type`, or "stdio") with a string `command`; http/sse/ws
//     entries already serve many clients and pass through untouched;
//   - no `cwd`, no `${VAR}` expansion in any value (expanded by the CLIENT from its own
//     environment, so two heads may not agree on the result), and no arg/env value that is an
//     existing directory (a project root the server would otherwise be scoped to);
//   - not on the operator's exclude list.
package splice.core.launch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path

/** The launch tuple that IS a hosted server's identity: same tuple, same process. */
public data class McpServerSpec(
    val name: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
)

/** One planning pass over the operator's `mcpServers`: what is hosted, what is not and why, and
 *  the object to write into a head's `.claude.json` in place of the original. */
public data class McpPlan(
    val hosted: Map<String, McpServerSpec>,
    val passthrough: Map<String, String>,
    val rewritten: JsonObject,
)

/** Answers "is this string an existing directory?" — a seam so eligibility is testable without
 *  a filesystem and so the planner never touches disk in the materializer's abort-free window. */
public fun interface DirectoryProbe {
    public operator fun invoke(candidate: String): Boolean
}

/** Rewrites a head's shared `mcpServers` object before it is written; the materializer's seam. */
public fun interface McpRewrite {
    public operator fun invoke(global: JsonObject): JsonObject
}

public class McpSharing(
    private val enabled: Boolean,
    private val exclude: Set<String>,
    /** `http://127.0.0.1:<port>/mcp/` — the host's endpoint prefix; the server name is appended. */
    private val endpointPrefix: String,
    /** Read at plan time, never cached: the key may be minted after this object exists. */
    private val bearer: () -> String,
    private val isDirectory: DirectoryProbe = DirectoryProbe { Files.isDirectory(Path.of(it)) },
) {
    public fun plan(global: JsonObject): McpPlan {
        if (!enabled) return McpPlan(emptyMap(), global.keys.associateWith { "hosting disabled" }, global)
        val hosted = LinkedHashMap<String, McpServerSpec>()
        val passthrough = LinkedHashMap<String, String>()
        val rewritten = buildJsonObject {
            global.forEach { (name, entry) ->
                val spec = eligible(name, entry, passthrough)
                if (spec == null) {
                    put(name, entry)
                } else {
                    hosted[name] = spec
                    putJsonObject(name) {
                        put("type", "http")
                        put("url", endpointPrefix + name)
                        putJsonObject("headers") { put("Authorization", "Bearer " + bearer()) }
                    }
                }
            }
        }
        return McpPlan(hosted, passthrough, rewritten)
    }

    /** The materializer-facing seam: the rewritten object, nothing else. */
    public fun rewrite(): McpRewrite = McpRewrite { plan(it).rewritten }

    /** The spec for [name] as the operator's file declares it now, or null when it is not hosted. */
    public fun hostedSpec(global: JsonObject, name: String): McpServerSpec? = plan(global).hosted[name]

    private fun eligible(name: String, entry: JsonElement, reasons: MutableMap<String, String>): McpServerSpec? {
        val obj = entry as? JsonObject
        val type = obj?.let { str(it["type"]) }
        val command = obj?.let { str(it["command"]) }
        val args = (obj?.get("args") as? JsonArray)?.mapNotNull { str(it) } ?: emptyList()
        val env = (obj?.get("env") as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { k to it } }?.toMap()
            ?: emptyMap()
        val values = args + env.values
        val directory = values.firstOrNull { it.startsWith("/") && isDirectory(it) }
        val rejection = when {
            name in exclude -> "excluded by [daemon] mcp_hosting_exclude"
            obj == null -> "entry is not an object"
            type != null && type != "stdio" -> "transport '$type' already serves many clients"
            command == null -> "no command"
            obj.containsKey("cwd") -> "has a cwd (session-scoped)"
            values.any { it.contains("\${") } -> "a value expands \${VAR} from the client's environment"
            directory != null -> "names the directory '$directory' (project-scoped)"
            else -> null
        }
        return if (rejection == null && command != null) {
            McpServerSpec(name, command, args, env)
        } else {
            reasons[name] = rejection ?: "no command"
            null
        }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
