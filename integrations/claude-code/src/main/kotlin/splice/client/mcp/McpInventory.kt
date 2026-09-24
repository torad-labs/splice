// NEW: V4-146 (2026-09-20) — the census over McpSources.kt's five readers: one report, a per-kind
// count that is its own assertion, and a disposition (migrated / excluded / pending) for every
// server found, on every kind, not only the one this daemon happens to rewrite.
//
// REFUSAL BY CONSTRUCTION is the row's mutation test: McpInventory takes a Map<McpSourceKind,
// McpSourceReader> and its `init` requires every McpSourceKind to have one. Delete a kind's reader
// from the wiring (the exact regression this row exists to catch — McpGlobalRead alone, again) and
// construction throws NAMING the missing kind, instead of a census quietly running four readers and
// reporting a smaller number as if it were complete.
//
// A HEAD'S COPY IS THIS DAEMON'S OWN (v0.4.0 mcp review): ClaudeHomes hands the GLOBAL reader every
// `~/.claude*` root, and each head that shares mcps is one of them, holding the canonical servers the
// materializer rewrote into it at launch. Such a server takes the canonical plan's answer and a reason
// naming the copy; only a root no head materializes is another identity's.
//
// "UNCLASSIFIED FAILS BY NAME": [disposition] is an exhaustive `when` over the closed McpSourceKind
// enum with no `else` — a sixth kind fails the BUILD until every branch below names it, which is
// stronger than a runtime report nobody reads.
package splice.client.mcp

import splice.client.Keys
import java.nio.file.Path

/** Why a registration is or is not shared-hosted. */
public enum class McpDisposition { MIGRATED, EXCLUDED, PENDING }

public data class McpDispositioned(
    val registration: McpRegistration,
    val disposition: McpDisposition,
    val reason: String,
)

/** One kind's shape in the report — sizes only; McpStatus renders these onto the wire. */
public data class McpKindCensus(
    val kind: McpSourceKind,
    val rootsScanned: Int,
    val filesScanned: Int,
    val registrations: Int,
)

public data class McpCensusReport(
    val kinds: List<McpKindCensus>,
    val dispositioned: List<McpDispositioned>,
)

/** The canonical home's plan, read fresh every census (never cached — same rule as every other MCP
 *  state read in this daemon): what McpSharing actually decided for the file the materializer
 *  rewrites. The census asks THIS, rather than re-deriving eligibility itself, so the two can never
 *  disagree about why one server did or did not move. */
public fun interface McpGlobalPlan {
    public operator fun invoke(): McpPlan
}

public class McpInventory(
    private val readers: Map<McpSourceKind, McpSourceReader>,
    /** The one GLOBAL file the materializer reads its servers from; a GLOBAL file outside
     *  [materializedHomes] is a different Claude identity this daemon does not touch. */
    private val canonicalGlobalFile: Path,
    private val canonicalPlan: McpGlobalPlan,
    /** The CLAUDE_CONFIG_DIR of every head whose `.claude.json` servers the materializer writes from
     *  [canonicalGlobalFile] through the plan: each head whose policy shares mcps. No default: a
     *  census built without the heads reports every copy this daemon wrote as another identity's. */
    materializedHomes: Set<Path>,
) {
    private val headCopies: Set<Path> = materializedHomes.map { normal(it.resolve(Keys.CLAUDE_JSON)) }.toSet()

    init {
        val missing = McpSourceKind.entries - readers.keys
        check(missing.isEmpty()) {
            "McpInventory built without a reader for ${missing.joinToString()} — refusing to census; " +
                "a partial reader map would report a smaller number as if it were complete (V4-146)"
        }
    }

    public fun census(): McpCensusReport {
        val scans = McpSourceKind.entries.map { kind -> readers.getValue(kind)() }
        val plan = canonicalPlan()
        val kinds = scans.map { s ->
            McpKindCensus(s.kind, s.rootsScanned.size, s.filesScanned.size, s.registrations.size)
        }
        val dispositioned = scans.flatMap { scan -> scan.registrations.map { disposition(it, plan) } }
        return McpCensusReport(kinds, dispositioned)
    }

    private fun disposition(reg: McpRegistration, plan: McpPlan): McpDispositioned = when (reg.kind) {
        McpSourceKind.GLOBAL -> globalDisposition(reg, plan)
        McpSourceKind.PROJECT -> McpDispositioned(reg, McpDisposition.PENDING, McpDispositionReasons.PROJECT)
        McpSourceKind.REPO -> McpDispositioned(reg, McpDisposition.PENDING, McpDispositionReasons.REPO)
        McpSourceKind.PLUGIN_MCP_JSON -> McpDispositioned(reg, McpDisposition.EXCLUDED, McpDispositionReasons.PLUGIN)
        McpSourceKind.PLUGIN_INLINE -> McpDispositioned(reg, McpDisposition.EXCLUDED, McpDispositionReasons.PLUGIN)
    }

    private fun globalDisposition(reg: McpRegistration, plan: McpPlan): McpDispositioned = when {
        reg.sourceFile == canonicalGlobalFile -> canonicalDisposition(reg, plan)
        normal(reg.sourceFile) in headCopies -> headCopyDisposition(reg, plan)
        else -> McpDispositioned(reg, McpDisposition.EXCLUDED, McpDispositionReasons.OTHER_HOME)
    }

    private fun canonicalDisposition(reg: McpRegistration, plan: McpPlan): McpDispositioned = when {
        plan.hosted.containsKey(reg.name) ->
            McpDispositioned(reg, McpDisposition.MIGRATED, McpDispositionReasons.MIGRATED)
        plan.passthrough.containsKey(reg.name) ->
            McpDispositioned(reg, McpDisposition.EXCLUDED, plan.passthrough.getValue(reg.name))
        else -> McpDispositioned(reg, McpDisposition.EXCLUDED, McpDispositionReasons.RACE)
    }

    private fun headCopyDisposition(reg: McpRegistration, plan: McpPlan): McpDispositioned = when {
        plan.hosted.containsKey(reg.name) ->
            McpDispositioned(reg, McpDisposition.MIGRATED, McpDispositionReasons.HEAD_COPY_MIGRATED)
        plan.passthrough.containsKey(reg.name) -> McpDispositioned(
            reg,
            McpDisposition.EXCLUDED,
            McpDispositionReasons.HEAD_COPY_AS_DECLARED + plan.passthrough.getValue(reg.name),
        )
        else -> McpDispositioned(reg, McpDisposition.EXCLUDED, McpDispositionReasons.HEAD_COPY_NOT_CANONICAL)
    }

    /** One spelling per file: a head's config dir comes from the topology, a registration's path from a
     *  directory listing, and the two meet only once both are absolute and normalised. */
    private fun normal(path: Path): Path = path.toAbsolutePath().normalize()
}

/** The written reasons LAW [2026-09-18] asks for — one place so McpInventory's `when` stays a
 *  lookup, not a paragraph per branch. */
internal object McpDispositionReasons {
    const val MIGRATED: String =
        "hosted once by the daemon; every session's Claude Code connects to the shared process " +
            "instead of spawning its own"
    const val PROJECT: String =
        "a project-scoped mcpServers override inside .claude.json; discovered but shared hosting does not rewrite it " +
            "yet (V4-146 is a census, not a migration of this kind)"
    const val REPO: String =
        "declared in the repo's own .mcp.json; discovered but shared hosting does not rewrite a committed manifest " +
            "(V4-146 is a census, not a migration of this kind)"
    const val PLUGIN: String =
        "plugin-owned manifest (read-only input); Claude Code's plugin precedence matches by ENDPOINT, not name, so " +
            "a user-scope entry pointing at the daemon would ADD a shared process rather than replace the plugin's " +
            "stdio one — migrating this kind needs disabledMcpServers plus a user-scope override, never a rewrite " +
            "of the plugin's own file (docs: Scope hierarchy and precedence)"
    const val OTHER_HOME: String =
        "declared in a different Claude Code identity's .claude.json; this daemon's materializer reads only its own " +
            "canonical home, so this entry is not this daemon's to rewrite"
    const val HEAD_COPY_MIGRATED: String =
        "a splice head's copy of the canonical home's entry, which this daemon's materializer rewrote into " +
            "that head at launch; it connects to the same shared process the canonical entry is hosted as"
    const val HEAD_COPY_AS_DECLARED: String =
        "a splice head's copy of the canonical home's entry, written into that head at launch and left as " +
            "declared: "
    const val HEAD_COPY_NOT_CANONICAL: String =
        "in a splice head's materialized .claude.json but not among the canonical home's servers, the only " +
            "ones hosting reads: added in that head after its launch, or left from an earlier canonical file"
    const val RACE: String =
        "read on a separate pass over the same file that disagreed with the plan just computed — most likely an " +
            "operator edit mid-census; recompute on the next call"
}
