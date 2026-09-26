// NEW: V4-283 — a folder the operator trusted in Claude Code is trusted in every head. Each head keeps
// its own .claude.json, so claudex -r of a claude-splice session in /tmp/tally-resume-3 met Claude
// Code's "Quick safety check" (take-resume-3, 2026-09-26) although the operator had trusted that
// folder in claude-splice.
//
// RECORDS, NEVER A VERDICT. Claude Code 2.1.283 trusts a cwd when `projects[<cwd>]` does, or when its
// walk (bundle fn dS) finds a trusted record from the cwd upward, bounded by the git root. So a launch
// carries exactly the records the operator already granted, in the operator's ~/.claude.json or any
// other head's, for the cwd and its ancestors, and that walk decides what they cover, the same as in
// the head the operator trusted from. A child or sibling of the cwd is never carried: the walk never
// reads it. Home and the root never are either: Claude Code keeps a home trust for the session only.
// A record is carried as Claude Code's own accept writes it (bundle fn cS: the entry it had, or none,
// with hasTrustDialogAccepted true), keyed verbatim from the source, so its spelling is Claude Code's.
// A false in this head is no refusal to respect: it is the default entry (bundle var Vte).
package splice.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

private const val PROJECTS_FIELD = "projects"
private const val TRUST_ACCEPTED = "hasTrustDialogAccepted"

/** A launch's folder-trust inputs: where it starts, and every OTHER head's CLAUDE_CONFIG_DIR, whose
 *  `.claude.json` records are sources beside the operator's own. */
public data class TrustedLaunch(val cwd: Path, val headConfigDirs: List<Path>)

internal class FolderTrust(private val home: Path, private val reads: JsonStateReads) {
    private val realPath = RealPathOrSelf()

    /** [state] with every record carried for [launch] trusted in its `projects`, and every other key
     *  and entry as it was. [operator] is the operator's own ~/.claude.json, already read. */
    fun seed(state: JsonObject, launch: TrustedLaunch?, operator: JsonObject): JsonObject {
        val carried = launch?.let { carried(it, operator) }.orEmpty()
        if (carried.isEmpty()) return state
        val projects = state[PROJECTS_FIELD] as? JsonObject ?: JsonObject(emptyMap())
        val trusted = carried.associateWith { path ->
            JsonObject((projects[path] as? JsonObject).orEmpty() + (TRUST_ACCEPTED to JsonPrimitive(true)))
        }
        return JsonObject(state + (PROJECTS_FIELD to JsonObject(projects + trusted)))
    }

    /** The trusted paths of every source that are the cwd or one of its ancestors. */
    private fun carried(launch: TrustedLaunch, operator: JsonObject): Set<String> {
        val lineage = lineage(launch.cwd)
        val heads = launch.headConfigDirs.map { reads.tolerant(it.resolve(Keys.CLAUDE_JSON)) }
        return (listOf(operator) + heads).flatMap(::trustedPaths).filter { it in lineage }.toSet()
    }

    /** The cwd and each ancestor, spelled as given and as the real path, less home and the root. */
    private fun lineage(cwd: Path): Set<String> {
        if (!cwd.isAbsolute) return emptySet()
        val homes = setOf(home.toAbsolutePath().normalize(), realPath(home))
        return listOf(cwd.normalize(), realPath(cwd))
            .flatMap { start -> generateSequence(start) { it.parent }.toList() }
            .filter { it.parent != null && it !in homes }
            .map(Path::toString)
            .toSet()
    }

    private fun trustedPaths(state: JsonObject): List<String> {
        val projects = state[PROJECTS_FIELD] as? JsonObject ?: return emptyList()
        return projects.filterValues { (it as? JsonObject)?.get(TRUST_ACCEPTED) == JsonPrimitive(true) }.keys.toList()
    }
}
