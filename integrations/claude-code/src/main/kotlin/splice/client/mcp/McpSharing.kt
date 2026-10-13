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
//   - not on the operator's exclude list;
//   - never seen asking its client for roots: the host offers roots and answers none, so a server that
//     depends on them shows itself by asking, and from then on its launch tuple launches per session.
//
// MCP hub step 3 (docs/specs/mcp-hub.md, splice-lead, Oct 12): every stdio server with a command is now CLASSED,
// shared or per session, and the class is decided in this order: the setup answer (share by default, yes or no),
// then evidence that forces per session (a cwd, a ${VAR}, a directory argument, roots asked), then the built-in list
// of servers known to keep state per process, starting with Desktop Commander. A per-session server keeps its
// declared tuple in [McpPlan.perSession], so the hub can start one copy per session in that session's folder with
// that session's environment. Everything else (excluded, unreadable, a web transport, no command) is the server's
// own, not run by the hub.
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

/** One refusal: the sentence the CLI prints and the key and value the console words. [perSession] says the server
 *  still runs through the hub, one copy per session, rather than not at all. */
private data class Rejected(val words: String, val why: McpWhy, val perSession: Boolean = false)

// why: servers known to keep state per process (mcp-hub.md step 3): one copy shared by every session would mix
// their terminals, their config and their history. Matched by package name, with or without a version.
private val STATEFUL_PACKAGES = setOf("@wonderwhy-er/desktop-commander", "desktop-commander")

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

/** The setup answer, read at every decision so a change applies to the next connection: true shares every server
 *  that can be shared, false starts one copy per session (MCP hub step 3). */
public fun interface McpShareChoice {
    public fun shared(): Boolean
}

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
    /** The launch tuples a hosted copy has seen asking its client for roots, kept across restarts by the daemon. */
    private val rootsBound: McpRootsVerdicts = McpRootsVerdicts(),
    /** The setup answer (hub step 3), `[daemon] mcp_share`, read at every plan. */
    private val shareByDefault: McpShareChoice = McpShareChoice { true },
) {
    /** A hosted server asked its client for roots, so it depends on them: no later plan shares its launch tuple,
     *  and every session from the next on starts its own copy, as for a server that names a project directory. */
    public fun rootsAsked(spec: McpServerSpec) {
        rootsBound.record(spec)
    }

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
        val perSession = LinkedHashMap<String, McpServerSpec>()
        val rewritten = buildJsonObject {
            global.forEach { (name, entry) ->
                val spec = eligible(name, entry, Refusals(passthrough, whys, perSession))
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
        return McpPlan(hosted, passthrough, rewritten, whys, perSession)
    }

    /** The materializer-facing seam: the rewritten object, nothing else. */
    public fun rewrite(): McpRewrite = McpRewrite { global, headKey -> plan(global, headKey).rewritten }

    /** The spec for [name] as the operator's file declares it now, or null when it is not hosted. */
    public fun hostedSpec(global: JsonObject, name: String): McpServerSpec? = plan(global).hosted[name]

    /** Where one plan's refusals go: the words, the key and value, and the per-session servers with their tuples. */
    private data class Refusals(
        val reasons: MutableMap<String, String>,
        val whys: MutableMap<String, McpWhy>,
        val perSession: MutableMap<String, McpServerSpec>,
    )

    private fun eligible(name: String, element: JsonElement, out: Refusals): McpServerSpec? {
        val entry = Entry(element as? JsonObject)
        val spec = entry.command?.let { McpServerSpec(name, it, entry.args, entry.env, entry.cwd) }
        val rejection = rejection(name, entry) ?: spec?.let(::perProcess)
        return if (rejection == null && spec != null) {
            spec
        } else {
            out.reasons[name] = rejection?.words ?: "no command"
            out.whys[name] = rejection?.why ?: McpWhy("no_command")
            if (rejection?.perSession == true && spec != null) out.perSession[name] = spec
            null
        }
    }

    /** Evidence the tuple alone cannot show: it asked for roots, or it is a server known to keep state per process. */
    private fun perProcess(spec: McpServerSpec): Rejected? {
        val stateful = (listOf(spec.command) + spec.args).map(::packageOf).firstOrNull { it in STATEFUL_PACKAGES }
        return when {
            rootsBound.asked(spec) ->
                Rejected("asks the client for its roots (project-scoped)", McpWhy("roots"), perSession = true)
            stateful != null ->
                Rejected("keeps state per process (one copy per session)", McpWhy("stateful", stateful), true)
            else -> null
        }
    }

    /** A token's package name: its last path part, without an `@version` after the name. */
    private fun packageOf(token: String): String {
        val versionAt = token.lastIndexOf('@')
        val bare = if (versionAt > 0) token.substring(0, versionAt) else token
        return if (bare.startsWith("@")) bare else bare.substringAfterLast('/')
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
        !shareByDefault.shared() ->
            Rejected("you chose one copy per session", McpWhy("per_session_choice"), perSession = true)
        entry.obj?.containsKey("cwd") == true -> Rejected(
            "has a cwd (session-scoped)",
            McpWhy("cwd", str(entry.obj["cwd"])),
            perSession = true,
        )
        else -> valueRejection(listOf(entry.command) + entry.args + entry.env.values)?.copy(perSession = true)
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
        val cwd: String? = obj?.let { str(it["cwd"]) }
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
