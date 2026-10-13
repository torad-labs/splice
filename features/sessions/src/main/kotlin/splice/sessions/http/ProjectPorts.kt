// NEW: Oct 12, 2026 — the ports the project views read the resolver and the settings through, apart from the routes.
package splice.sessions.http

import splice.core.compaction.CompactionInstructions
import splice.sessions.registry.ProjectRef
import splice.sessions.registry.SessionRecord

/** A session's project, as the sessions rows resolve it: the same answer the Teams and request rows use. */
public fun interface ProjectOf {
    public operator fun invoke(record: SessionRecord): ProjectRef?
}

/** The Projects folders saved in settings, as written. */
public fun interface ProjectFolders {
    public operator fun invoke(): List<String>
}

/** The project a working directory belongs to (a team's repo), asked of the same resolver. */
public fun interface ProjectAt {
    public operator fun invoke(cwd: String): ProjectRef?
}

/** The daemon's compaction table, read at CALL time: the control plane assigns it after the routes are
 *  built, so a value captured at construction would be null forever. Null is "never wired". */
public fun interface CompactionSource {
    public operator fun invoke(): CompactionInstructions?
}

/** Everything the project views ask the resolver: a session's project, a working directory's project, and the
 *  saved Projects folders. */
public data class ProjectLookup(
    public val of: ProjectOf,
    public val at: ProjectAt,
    public val folders: ProjectFolders = ProjectFolders { emptyList() },
)
