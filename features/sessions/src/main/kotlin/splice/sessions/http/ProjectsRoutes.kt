// NEW: V4-131, FEATURES.md 4.14 and 6 — the project routes, a view per git repo:
//
//   GET /api/projects              {projects: ProjectRow[]}
//   GET /api/projects/{id}         one ProjectRow
//   GET /api/projects/{id}/files   {id, files, looked_in, auto_memory_enabled?}
//
// A PROJECT IS A GIT ROOT and its id is that root. The roots include durable transcript/history
// sessions as well as registry rows (using the Sessions route's RepoResolver: trusted roots only,
// worktrees folded into their repo), plus each team's declared repo after its sessions have ended.
//
// TODAY IS THE UTC DAY, and the row says where it started (day_start) rather than letting the console
// guess a boundary. turns_today and cost_today_usd join the perf rows of every head on the 8-character
// session tag, over the repo's registry sessions and every session its teams' slots ever held. The
// dollars are the priced turns' (PerfTally's V4-221 rule), and unpriced_turns_today counts the turns
// no card priced beside them; the dollars are null only when there are turns and none was priced,
// never zero for "unknown" (V4-269).
//
// WHAT GOVERNS THE REPO (FEATURES.md 4.14, "its compaction scope and the effective instructions, the
// statusline roots entry"). `compaction` is core's own answer for this root —
// CompactionInstructions.rulesFor, the rules a compaction here can resolve to in resolve's precedence,
// shadowed ones left out — as {scope, source, chars}, the shape GET /api/compaction/instructions
// uses; no instruction text crosses the wire, for that route's reason. An empty list means no rule
// applies and the client's own instructions stand; null means the daemon never wired the table,
// which is not the same fact. `statusline_roots` is one entry per head, because statuslineGitRoots
// is per-head overridable: the trusted root that head's statusline probes this repo under (home, tmp
// or statuslineGitRoots) and the root itself, both null when the repo is outside every one, where
// that head's statusline shows no branch.
//
// FILES ARE READ ONLY FROM A KNOWN PROJECT. {id} must be one of the roots the list reports, or the
// route is a 404: a path from the URL is never read on its own say-so. The repo's own instruction
// files (CLAUDE.md, AGENTS.md at the root), then each head's client memory at
// `<head config dir>/projects/<slug>/memory/*.md`, the slug being Claude Code's own (every character
// outside [A-Za-z0-9] becomes '-', measured against the trees on disk: `.../v0.4.0` is `...-v0-4-0`).
// Nothing is ever written. looked_in names every directory searched, so an empty list names where it
// looked. auto_memory_enabled is the heads' `autoMemoryEnabled` setting when every head that states
// it agrees, and absent when none states it or they disagree, since one boolean cannot carry both.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.compaction.CompactionInstructions
import splice.core.config.ProjectFolderCheck
import splice.core.util.WallClock
import splice.http.JsonReply
import splice.sessions.projects.ProjectEconomics
import splice.sessions.projects.ProjectFiles
import splice.sessions.query.SessionHead
import splice.sessions.registry.ProjectRef
import splice.sessions.registry.RepoOrigin
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.registry.TrustedRoot
import java.nio.file.Paths

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

/** The trusted root a head's statusline probes a path under, as the sessions rows' resolver sees it. */
public fun interface StatuslineRootOf {
    public operator fun invoke(path: String, head: String): TrustedRoot?
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

public class ProjectsRoutes(
    private val registry: SessionSource?,
    private val heads: Map<String, SessionHead>,
    private val lookup: ProjectLookup,
    private val teams: TeamSource,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val statuslineRoot: StatuslineRootOf = StatuslineRootOf { _, _ -> null },
    private val compaction: CompactionSource = CompactionSource { null },
) {
    public fun list(): JsonReply {
        val view = ProjectView()
        val body = buildJsonObject {
            put("projects", buildJsonArray { view.roots.forEach { add(view.row(it)) } })
            // A saved folder that has vanished is kept and reported, never a reason to refuse: its sessions fall back to repos.
            put(
                "folders",
                buildJsonArray {
                    lookup.folders().forEach { path ->
                        add(buildJsonObject { put("path", path); put("found", ProjectFolderCheck.found(path)) })
                    }
                },
            )
        }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }

    public fun project(id: String): JsonReply {
        val view = ProjectView()
        if (id !in view.roots) return unknown(id)
        return JsonReply(HttpStatusCode.OK, view.row(id).toString())
    }

    public fun files(id: String): JsonReply {
        if (id !in ProjectView().roots) return unknown(id)
        return JsonReply(HttpStatusCode.OK, ProjectFiles(heads).read(id).toString())
    }

    private fun unknown(id: String): JsonReply {
        val body = buildJsonObject { put("error", "not a project root splice has seen: $id") }
        return JsonReply(HttpStatusCode.NotFound, body.toString())
    }

    /** One read of the registry, the teams and today's perf rows, shared by every row of a request. */
    private inner class ProjectView {
        private val records = registry?.read().orEmpty()
        private val allTeams = teams()?.teams().orEmpty()
        private val placed = records.mapNotNull { record -> lookup.of(record)?.let { it to record } }
        private val byRoot = placed.groupBy({ it.first.id }, { it.second })
        private val teamProject =
            allTeams.associateWith { team -> team.repo.takeIf { it.isNotBlank() }?.let { lookup.at(it) } }
        private val refs: Map<String, ProjectRef> =
            (placed.map { it.first } + teamProject.values.filterNotNull()).associateBy { it.id }
        private val economics = ProjectEconomics(heads, clock())
        private val table = compaction()
        val roots: List<String> =
            refs.keys.sorted()

        fun row(root: String): JsonObject {
            val sessions = byRoot[root].orEmpty()
            val repoTeams = allTeams.filter { teamProject[it]?.id == root }
            return buildJsonObject {
                put("id", root)
                refs[root]?.let { put("name", it.name); put("label", it.label); put("source", it.source.wire) }
                put("root", root)
                RepoOrigin.of(root)?.let { put("remote", it) }
                economics.row(sessions, repoTeams).forEach { (key, value) -> put(key, value) }
                put("compaction", compactionOf(root))
                put("statusline_roots", statuslineRootsOf(root))
            }
        }

        private fun compactionOf(root: String) = table?.let { rules ->
            buildJsonArray {
                rules.rulesFor(Paths.get(root)).forEach { rule ->
                    add(
                        buildJsonObject {
                            put("scope", rule.scope.wire)
                            put("source", rule.source)
                            // live length: 0 for an explicit opt-out, null when the file is unreadable
                            // (the source label says so too) — CompactionInstructionsRoute's rule.
                            put("chars", rule.text?.length)
                        },
                    )
                }
            }
        } ?: JsonNull

        private fun statuslineRootsOf(root: String) = buildJsonArray {
            heads.keys.sorted().forEach { head ->
                val covering = statuslineRoot(root, head)
                add(
                    buildJsonObject {
                        put("head", head)
                        put("root", covering?.path)
                        put("entry", covering?.origin?.wire)
                    },
                )
            }
        }
    }
}
