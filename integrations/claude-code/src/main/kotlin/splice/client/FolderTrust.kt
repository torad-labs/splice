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

/** A launch's folder-trust inputs: where it starts, every OTHER head's CLAUDE_CONFIG_DIR, whose
 *  `.claude.json` records are sources beside the operator's own, and the folder a move in flight
 *  carries trust for, which is the one way home is ever trusted ([FolderTrust]). */
public data class TrustedLaunch(val cwd: Path, val headConfigDirs: List<Path>, val moving: Path? = null)

internal class FolderTrust(private val home: Path, private val reads: JsonStateReads) {
    private val realPath = RealPathOrSelf()

    /** [state] with every record carried for [launch] trusted in its `projects`, and every other key
     *  and entry as it was. [operator] is the operator's own ~/.claude.json, already read.
     *
     *  SPLICE OWNS THE HOME RECORD, and writes one only for the launch of a move that is carrying it
     *  ([TrustedLaunch.moving]). Claude Code keeps a home trust for the session only (its
     *  `sessionFlags.homeTrustDialogAccepted`, bundle fn vwe), so a session running in home has a
     *  trust the operator granted and nothing on disk says so: resumed on another command, the move
     *  would stop at the safety check with his agent already ended. The record is what carries that
     *  one grant across, and every launch not carrying it takes the record back out, so the grant
     *  never outlives the move that needed it (Marlin, Oct 11). */
    fun seed(state: JsonObject, launch: TrustedLaunch?, operator: JsonObject): JsonObject {
        val carried = launch?.let { carried(it, operator) }.orEmpty()
        val projects = state[PROJECTS_FIELD] as? JsonObject ?: JsonObject(emptyMap())
        val stale = homeKeys().filterNot { it in carried }.filter { it in projects }
        if (carried.isEmpty() && stale.isEmpty()) return state
        val trusted = carried.associateWith { path ->
            JsonObject((projects[path] as? JsonObject).orEmpty() + (TRUST_ACCEPTED to JsonPrimitive(true)))
        }
        return JsonObject(state + (PROJECTS_FIELD to JsonObject(projects + trusted - stale.toSet())))
    }

    /** How home is spelled as a `projects` key: as given and as the real path. */
    private fun homeKeys(): Set<String> =
        setOf(home.toAbsolutePath().normalize().toString(), realPath(home).toString())

    /** The trusted paths of every source that are the cwd or one of its ancestors, and home when the
     *  move in flight is moving out of it.
     *
     *  HOME ONLY, because home is the only folder whose grant leaves nothing to read: every other
     *  folder the operator trusted has a record in some head's file, which [lineage] already carries,
     *  and a folder he never trusted must keep asking. The move's own evidence is that a client was
     *  live in that folder, which Claude Code only does once the check has passed. */
    private fun carried(launch: TrustedLaunch, operator: JsonObject): Set<String> {
        val lineage = lineage(launch.cwd)
        val heads = launch.headConfigDirs.map { reads.tolerant(it.resolve(Keys.CLAUDE_JSON)) }
        val granted = (listOf(operator) + heads).flatMap(::trustedPaths).filter { it in lineage }
        val moving = launch.moving
            ?.takeIf { it.isAbsolute }
            ?.let { setOf(it.normalize().toString(), realPath(it).toString()) }
            ?.intersect(homeKeys())
            .orEmpty()
        return (granted + moving).toSet()
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
