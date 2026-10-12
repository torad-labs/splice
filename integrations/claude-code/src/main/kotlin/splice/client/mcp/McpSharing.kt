// NEW: v0.4.0 FEATURES.md §8 — decides which of the operator's MCP servers splice may host ONCE
// for every session, and rewrites their entries so each head's Claude Code connects to the host
// over HTTP instead of spawning its own copy. Framework-free on purpose: the host itself lives in
// :daemon-control; this planner is the single source of truth both the materializer (what to rewrite)
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
)

/** One refusal: the sentence the CLI prints and the key and value the console words. */
private data class Rejected(val words: String, val why: McpWhy)

/** Answers "is this string an existing directory?" — a seam so eligibility is testable without
 *  a filesystem and so the planner never touches disk in the materializer's abort-free window. */
public fun interface DirectoryProbe {
    public operator fun invoke(candidate: String): Boolean
}

/** Rewrites a head's shared `mcpServers` object before it is written; the materializer's seam. [headKey] is the
 *  command the file is written for: a hosted entry names it in a header, so the host can say which commands use a
 *  server. */
public fun interface McpRewrite {
    public operator fun invoke(global: JsonObject, headKey: String): JsonObject
}

/** The header a hosted entry carries so the host knows which command a session belongs to. */
public const val MCP_HEAD_HEADER: String = "X-Splice-Head"

private val LOCATION_FLAG =
    Regex("--?(root|roots?-?dir|dir|directory|path|cwd|workspace|project|folder|home|base-?dir|work-?dir)")
private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val VARIABLE = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)""")

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
    public fun plan(global: JsonObject, headKey: String? = null): McpPlan {
        if (!enabled) {
            return McpPlan(
                emptyMap(),
                global.keys.associateWith { "hosting disabled" },
                global,
                global.keys.associateWith { McpWhy("hosting_disabled") },
            )
        }
        val hosted = LinkedHashMap<String, McpServerSpec>()
        val passthrough = LinkedHashMap<String, String>()
        val whys = LinkedHashMap<String, McpWhy>()
        val rewritten = buildJsonObject {
            global.forEach { (name, entry) ->
                val spec = eligible(name, entry, passthrough, whys)
                if (spec == null) {
                    put(name, entry)
                } else {
                    hosted[name] = spec
                    putJsonObject(name) {
                        put("type", "http")
                        put("url", endpointPrefix + name)
                        putJsonObject("headers") {
                            put("Authorization", "Bearer " + bearer())
                            headKey?.takeIf { it.isNotEmpty() }?.let { put(MCP_HEAD_HEADER, it) }
                        }
                    }
                }
            }
        }
        return McpPlan(hosted, passthrough, rewritten, whys)
    }

    /** The materializer-facing seam: the rewritten object, nothing else. */
    public fun rewrite(): McpRewrite = McpRewrite { global, headKey -> plan(global, headKey).rewritten }

    /** The spec for [name] as the operator's file declares it now, or null when it is not hosted. */
    public fun hostedSpec(global: JsonObject, name: String): McpServerSpec? = plan(global).hosted[name]

    private fun eligible(
        name: String,
        element: JsonElement,
        reasons: MutableMap<String, String>,
        whys: MutableMap<String, McpWhy>,
    ): McpServerSpec? {
        val entry = Entry(element as? JsonObject)
        val rejection = rejection(name, entry)
        return if (rejection == null && entry.command != null) {
            McpServerSpec(name, entry.command, entry.args, entry.env)
        } else {
            reasons[name] = rejection?.words ?: "no command"
            whys[name] = rejection?.why ?: McpWhy("no_command")
            null
        }
    }

    private fun rejection(name: String, entry: Entry): Rejected? = when {
        name in exclude -> Rejected("excluded by [daemon] mcp_hosting_exclude", McpWhy("excluded"))
        entry.obj == null -> Rejected("entry is not an object", McpWhy("unreadable"))
        entry.malformed != null -> Rejected(entry.malformed, McpWhy("unreadable"))
        else -> launchRejection(entry)
    }

    private fun launchRejection(entry: Entry): Rejected? = when {
        entry.type != null && entry.type != "stdio" -> Rejected(
            "transport '${entry.type}' already serves many clients",
            McpWhy("transport", entry.url ?: entry.type),
        )
        entry.command == null -> Rejected("no command", McpWhy("no_command"))
        entry.obj?.containsKey("cwd") == true -> Rejected(
            "has a cwd (session-scoped)",
            McpWhy("cwd", str(entry.obj["cwd"])),
        )
        else -> valueRejection(listOf(entry.command) + entry.args + entry.env.values)
    }

    private fun valueRejection(values: List<String>): Rejected? {
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
        val variable = values.firstNotNullOfOrNull { VARIABLE.find(it)?.groupValues?.get(1) }
        return when {
            values.any { it.contains("\${") } -> Rejected(
                "a value expands \${VAR} from the client's environment",
                McpWhy("variable", variable),
            )
            relative != null -> Rejected(
                "names the relative path '$relative' (project-scoped)",
                McpWhy("relative_path", relative),
            )
            located != null -> Rejected(
                "'$located' names a location relative to the client (project-scoped)",
                McpWhy("location_flag", located),
            )
            directory != null -> Rejected(
                "names the directory '$directory' (project-scoped)",
                McpWhy("directory", directory),
            )
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
        val url: String? = obj?.let { str(it["url"]) }
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
