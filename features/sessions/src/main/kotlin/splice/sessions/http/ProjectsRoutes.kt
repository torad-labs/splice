// NEW: V4-131, FEATURES.md 4.14 and 6 — the project routes, a view per git repo:
//
//   GET /api/projects              {projects: ProjectRow[]}
//   GET /api/projects/{id}         one ProjectRow
//   GET /api/projects/{id}/files   {id, files}
//
// A PROJECT IS A GIT ROOT and its id is that root. The roots include durable transcript/history
// sessions as well as registry rows (using the Sessions route's RepoResolver: trusted roots only,
// worktrees folded into their repo), plus each team's declared repo after its sessions have ended.
//
// WHAT GOVERNS THE REPO (FEATURES.md 4.14, "its compaction scope and the effective instructions").
// `compaction` is core's own answer for this root —
// CompactionInstructions.rulesFor, the rules a compaction here can resolve to in resolve's precedence,
// shadowed ones left out — as {scope, source, chars}, the shape GET /api/compaction/instructions
// uses; no instruction text crosses the wire, for that route's reason. An empty list means no rule
// applies and the client's own instructions stand; null means the daemon never wired the table,
// which is not the same fact.
//
// FILES ARE READ ONLY FROM A KNOWN PROJECT. {id} must be one of the roots the list reports, or the
// route is a 404: a path from the URL is never read on its own say-so. The repo's own instruction
// files (CLAUDE.md, AGENTS.md at the root), then each head's client memory at
// `<head config dir>/projects/<slug>/memory/*.md`, the slug being Claude Code's own (every character
// outside [A-Za-z0-9] becomes '-', measured against the trees on disk: `.../v0.4.0` is `...-v0-4-0`).
// Nothing is ever written.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.ProjectFolderCheck
import splice.http.JsonReply
import splice.sessions.projects.ProjectFiles
import splice.sessions.query.SessionHead
import splice.sessions.registry.ProjectRef
import splice.sessions.registry.SessionSource
import java.nio.file.Paths

public class ProjectsRoutes(
    private val registry: SessionSource?,
    private val heads: Map<String, SessionHead>,
    private val lookup: ProjectLookup,
    private val teams: TeamSource,
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

    /** One read of the registry and the teams, shared by every row of a request. */
    private inner class ProjectView {
        private val records = registry?.read().orEmpty()
        private val allTeams = teams()?.teams().orEmpty()
        private val placed = records.mapNotNull { record -> lookup.of(record)?.let { it to record } }
        private val teamProject =
            allTeams.associateWith { team -> team.repo.takeIf { it.isNotBlank() }?.let { lookup.at(it) } }
        private val refs: Map<String, ProjectRef> =
            (placed.map { it.first } + teamProject.values.filterNotNull()).associateBy { it.id }
        private val table = compaction()
        val roots: List<String> =
            refs.keys.sorted()

        fun row(root: String): JsonObject {
            return buildJsonObject {
                put("id", root)
                refs[root]?.let {
                    put("name", it.name)
                    put("label", it.label)
                    put("source", it.source.wire)
                    // the saved folder as spelled in folders[].path; null for a repo project
                    put("folder", it.folder)
                }
                put("root", root)
                put("compaction", compactionOf(root))
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
    }
}
