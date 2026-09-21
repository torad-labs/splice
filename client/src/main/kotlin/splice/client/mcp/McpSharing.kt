// NEW: v0.4.0 FEATURES.md §8 — decides which of the operator's MCP servers splice may host ONCE
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
package splice.client.mcp

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

private val LOCATION_FLAG =
    Regex("--?(root|roots?-?dir|dir|directory|path|cwd|workspace|project|folder|home|base-?dir|work-?dir)")
private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

/** The MCP-scoped bearer at plan time; generated client configs must not carry management authority. */
public fun interface McpBearer {
    public operator fun invoke(): String
}

public class McpSharing(
    public val enabled: Boolean,
    private val exclude: Set<String>,
    /** `http://127.0.0.1:<port>/mcp/` — the host's endpoint prefix; the server name is appended. */
    private val endpointPrefix: String,
    /** Read at plan time, never cached: the key may be minted after this object exists. */
    private val bearer: McpBearer,
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

    private fun eligible(name: String, element: JsonElement, reasons: MutableMap<String, String>): McpServerSpec? {
        val entry = Entry(element as? JsonObject)
        val rejection = rejection(name, entry)
        return if (rejection == null && entry.command != null) {
            McpServerSpec(name, entry.command, entry.args, entry.env)
        } else {
            reasons[name] = rejection ?: "no command"
            null
        }
    }

    private fun rejection(name: String, entry: Entry): String? = when {
        name in exclude -> "excluded by [daemon] mcp_hosting_exclude"
        entry.obj == null -> "entry is not an object"
        entry.malformed != null -> entry.malformed
        entry.type != null && entry.type != "stdio" -> "transport '${entry.type}' already serves many clients"
        entry.command == null -> "no command"
        entry.obj.containsKey("cwd") -> "has a cwd (session-scoped)"
        else -> valueRejection(listOf(entry.command) + entry.args + entry.env.values)
    }

    private fun valueRejection(values: List<String>): String? {
        // Every value is checked bare AND as a flag's payload (`--root=./repo`). A RELATIVE path is
        // resolved by the client against ITS cwd, which the host cannot know, so the classification
        // is fail-closed on shape alone (review 3, 2026-09-13): `.`/`./x`/`../x`, any relative token
        // with a separator that is not an npm scope (`@scope/pkg`) or a URL, and any payload of a
        // flag whose name says it is a location (--root, --dir, --path, --cwd, ...). Only an
        // ABSOLUTE path is probed as an existing directory — the daemon's cwd is the wrong cwd for
        // anything else.
        val candidates = values.flatMap { listOf(it, it.substringAfter('=', "")) }.filter { it.isNotEmpty() }
        val relative = candidates.firstOrNull(::looksRelative)
        val located = locationFlag(values)
        val directory = candidates.firstOrNull { it.startsWith("/") && isDirectory(it) }
        return when {
            values.any { it.contains("\${") } -> "a value expands \${VAR} from the client's environment"
            relative != null -> "names the relative path '$relative' (project-scoped)"
            located != null -> "'$located' names a location relative to the client (project-scoped)"
            directory != null -> "names the directory '$directory' (project-scoped)"
            else -> null
        }
    }

    /** A location flag with its payload — the `=` half OR the following token (`--root repo`): either
     *  way the value is a path the client resolves, so the pair is refused (review 4). */
    private fun locationFlag(values: List<String>): String? = values.withIndex()
        .firstOrNull { (i, v) ->
            LOCATION_FLAG.matches(v.substringBefore('=')) && (v.contains('=') || i + 1 < values.size)
        }
        ?.let { (i, v) -> if (v.contains('=')) v else "$v ${values[i + 1]}" }

    private fun looksRelative(value: String): Boolean = when {
        value == "." || value.startsWith("./") || value.startsWith("../") -> true
        value.startsWith("/") || value.startsWith("@") || value.startsWith("-") -> false
        URL_SCHEME.containsMatchIn(value) -> false
        else -> value.contains('/')
    }

    /** One `mcpServers` entry read once; every field nullable so a malformed entry rejects in words. */
    private inner class Entry(val obj: JsonObject?) {
        val type: String? = obj?.let { str(it["type"]) }
        val command: String? = obj?.let { str(it["command"]) }
        val args: List<String> = (obj?.get("args") as? JsonArray)?.mapNotNull { str(it) } ?: emptyList()
        val env: Map<String, String> =
            (obj?.get("env") as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { k to it } }?.toMap() ?: emptyMap()
        val malformed: String? = when {
            obj?.containsKey("type") == true && type == null -> "malformed transport type"
            obj?.containsKey("args") == true &&
                (obj["args"] !is JsonArray || (obj["args"] as JsonArray).any { str(it) == null }) ->
                "malformed args (expected only strings)"
            obj?.containsKey("env") == true &&
                (obj["env"] !is JsonObject || (obj["env"] as JsonObject).values.any { str(it) == null }) ->
                "malformed env (expected string values)"
            else -> null
        }
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
