// NEW: V4-146 (2026-09-20) — the four source kinds shared MCP hosting could not see. Before this
// file, McpGlobalRead (splice.app) read exactly one file, `<home>/.claude.json`'s top-level
// `mcpServers` — kind GLOBAL, one home. Measured on this box (hostshield-orchestrator, 2026-09-18,
// confirmed here): the operator has 15 `~/.claude*` roots — Claude Code relocates `.claude.json`
// itself under CLAUDE_CONFIG_DIR, so `~/.claude-bonsai/.claude.json`, `~/.claude-claude-grok/
// .claude.json`, … are real, independent files, never symlinks — plus PROJECT overrides nested
// inside every one of those files (`projects.<path>.mcpServers`), REPO manifests the operator's own
// repos carry (`.mcp.json`), and two plugin-owned kinds neither reader ever opened: a plugin's own
// `.mcp.json` and the inline `mcpServers` some plugins declare in `plugin.json`. This is LAW 24 in
// landed verified work: a denominator drawn from one source and never re-measured (V4-08's benchmark
// could only ever see this same one-tenth).
//
// THE CONTRACT (this file): the five kinds, one registration/scan/reader shape and the one shared
// JSON helper every reader uses — split from the readers themselves (ClaudeHomes.kt,
// McpGlobalProjectReaders.kt, McpRepoReader.kt, McpPluginReaders.kt) so no one file carries all
// five kinds' logic (gate:concentration; a single-file draft of this row measured band HIGH at
// 3.03x its neighbourhood). Every reader here is framework-free and testable without touching the
// operator's real files — this file is the source that reader and the census (McpInventory.kt)
// both read, so the two can never disagree about what exists. Each reader is a McpSourceReader so
// McpInventory can substitute a fixture in a test or catch a MISSING one at construction (the row's
// mutation test: delete a kind reader and the census refuses to run).
//
// SCOPE, STATED SO IT IS A DECISION: the materializer's REWRITE pipeline (McpSharing / McpHost) is
// UNCHANGED by this row — it still rewrites only the canonical home's GLOBAL servers
// (never-below-status-quo). These readers widen what the CENSUS can SEE and REPORT on; McpInventory
// decides what that visibility is worth per kind (migrated / excluded-with-a-reason / pending).
package splice.core.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink
import java.nio.file.Path

/** The five structurally different places an MCP server can be declared on this box. */
public enum class McpSourceKind {
    GLOBAL, PROJECT, REPO, PLUGIN_MCP_JSON, PLUGIN_INLINE,
}

/** One server NAME found in one FILE — the unit the census counts (the ledger's "139
 *  registrations" against "28 distinct servers": the same name legitimately repeats across kinds
 *  and files). [scope] is the project directory for PROJECT/REPO, the plugin's own directory for
 *  PLUGIN_*, null for GLOBAL. */
public data class McpRegistration(
    val kind: McpSourceKind,
    val name: String,
    val sourceFile: Path,
    val scope: Path?,
    val entry: JsonObject,
)

/** What one kind's reader looked at — [rootsScanned] is never empty by construction for a reader
 *  that was WIRED, so an empty one here is the wiring bug the row's mutation test proves: "was
 *  never given anywhere to look", never confused with [filesScanned] finding nothing there (a
 *  legitimate, silent zero — LAW [2026-09-18] EVERY INSTRUMENT DISTINGUISHES PASSED, FAILED AND
 *  DID NOT RUN). */
public data class McpSourceScan(
    val kind: McpSourceKind,
    val rootsScanned: List<Path>,
    val filesScanned: List<Path>,
    val registrations: List<McpRegistration>,
)

/** Reads one kind, once, never cached (matching McpGlobalRead's own tolerant-global-read ethos: an
 *  edit to the operator's files is visible on the next census). */
public fun interface McpSourceReader {
    public operator fun invoke(): McpSourceScan
}

// Shared across the reader files below (ClaudeHomes.kt, McpRepoReader.kt, McpPluginReaders.kt) —
// internal, not private, and declared ONCE here so no two files can drift on the same name
// (checks/const-single-source.ts).
internal const val PLUGINS_DIR: String = "plugins"
internal const val MCP_JSON_FILE: String = ".mcp.json"
internal const val PLUGIN_JSON_FILE: String = "plugin.json"
internal const val CLAUDE_PLUGIN_DIR: String = ".claude-plugin"
internal const val GIT_DIR: String = ".git"

// Deep enough to reach plugins/cache/<marketplace>/<plugin>/<version>/.claude-plugin/plugin.json
// (6 segments below plugins/) with headroom for a scoped package name or an extra version
// component; not unbounded, so a walk never free-runs into an arbitrarily deep tree.
internal const val PLUGIN_WALK_DEPTH: Int = 10

// One immutable empty object shared by every tolerant read that finds no `mcpServers` (or a
// malformed one) — file scope, same rule as JsonStateReads' own EMPTY_JSON (no companion object).
private val EMPTY_SERVERS = JsonObject(emptyMap())

/** One shared JSON reader every kind uses: tolerant (a bad file reads as no servers, matching the
 *  materializer's own global read — a corrupt file the operator has not touched never breaks the
 *  census) and self-contained (no cross-module type leaks through a reader's public ctor). */
internal class SourceJson {
    private val jsonReads = JsonStateReads(Json { ignoreUnknownKeys = true }, LogSink { })

    fun mcpServers(file: Path): JsonObject = jsonReads.tolerant(file)[Keys.MCP_SERVERS] as? JsonObject ?: EMPTY_SERVERS

    fun topLevel(file: Path): JsonObject = jsonReads.tolerant(file)

    /** [servers] (an `mcpServers` object) turned into one [McpRegistration] per key — the one place
     *  every kind builds its result, so a long constructor call is written once, not five times. */
    fun registrations(kind: McpSourceKind, file: Path, scope: Path?, servers: JsonObject): List<McpRegistration> =
        servers.map { (name, entry) -> McpRegistration(kind, name, file, scope, asObject(entry)) }

    private fun asObject(element: JsonElement): JsonObject = element as? JsonObject ?: EMPTY_SERVERS
}
