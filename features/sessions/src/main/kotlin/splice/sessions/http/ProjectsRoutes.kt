// NEW: V4-131, FEATURES.md 4.14 and 6 — the project routes, a view per git repo:
//
//   GET /api/projects              {projects: ProjectRow[]}
//   GET /api/projects/{id}         one ProjectRow
//   GET /api/projects/{id}/files   {id, files, looked_in, auto_memory_enabled?}
//
// A PROJECT IS A GIT ROOT and its id is that root. The roots are the registry sessions' repos (the
// RepoResolver the sessions rows use: trusted roots only, worktrees folded into their repo) plus
// every team's declared repo, so a team whose sessions have all ended still has its project.
//
// TODAY IS THE UTC DAY, and the row says where it started (day_start) rather than letting the console
// guess a boundary. turns_today and cost_today_usd join the perf rows of every head on the 8-character
// session tag, over the repo's registry sessions and every session its teams' slots ever held; the
// dollars are null when any counted turn had no rate card (TeamsRoutes' PerfTally), never a partial
// sum and never zero for "unknown".
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.compaction.CompactionInstructions
import splice.core.util.Cancellables
import splice.core.util.WallClock
import splice.http.JsonReply
import splice.sessions.query.SessionHead
import splice.sessions.query.SessionPerfWindow
import splice.sessions.registry.RepoRoot
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.registry.TrustedRoot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset

/** The repo files read as a project's own instructions, in the order they are listed. */
private val INSTRUCTION_FILES = listOf("CLAUDE.md", "AGENTS.md")
private const val MEMORY_SUFFIX = ".md"

// why: perf rows are keyed by the first 8 characters of a session id, so a lookup by full id
// never matches. Truncate here to the same width the perf store tagged with.
private const val PERF_TAG = 8

/** A session's repo, as the sessions rows resolve it. */
public fun interface RepoOf {
    public operator fun invoke(record: SessionRecord): RepoRoot?
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

public class ProjectsRoutes(
    private val registry: SessionSource?,
    private val heads: Map<String, SessionHead>,
    private val repoOf: RepoOf,
    private val teams: TeamSource,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val statuslineRoot: StatuslineRootOf = StatuslineRootOf { _, _ -> null },
    private val compaction: CompactionSource = CompactionSource { null },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val slugChars = Regex("[^A-Za-z0-9]")

    public fun list(): JsonReply {
        val view = ProjectView()
        val body = buildJsonObject { put("projects", buildJsonArray { view.roots.forEach { add(view.row(it)) } }) }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }

    public fun project(id: String): JsonReply {
        val view = ProjectView()
        if (id !in view.roots) return unknown(id)
        return JsonReply(HttpStatusCode.OK, view.row(id).toString())
    }

    public fun files(id: String): JsonReply {
        if (id !in ProjectView().roots) return unknown(id)
        val root = Paths.get(id)
        val slug = slugChars.replace(id, "-")
        val memoryDirs = heads.mapNotNull { (name, head) ->
            head.transcriptRoot?.let { name to it.resolve("projects").resolve(slug).resolve("memory") }
        }
        val lookedIn = listOf(root) + memoryDirs.map { it.second }
        val files = INSTRUCTION_FILES.mapNotNull { file(root.resolve(it), "instructions", null) } +
            memoryDirs.flatMap { (name, dir) -> memoryFiles(dir).mapNotNull { file(it, "memory", name) } }
        val body = buildJsonObject {
            put("id", id)
            put("files", buildJsonArray { files.forEach(::add) })
            put("looked_in", buildJsonArray { lookedIn.forEach { add(JsonPrimitive(it.toString())) } })
            autoMemory()?.let { put("auto_memory_enabled", it) }
        }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }

    private fun unknown(id: String): JsonReply {
        val body = buildJsonObject { put("error", "not a project root splice has seen: $id") }
        return JsonReply(HttpStatusCode.NotFound, body.toString())
    }

    private fun file(path: Path, kind: String, head: String?): JsonObject? {
        if (!Files.isRegularFile(path)) return null
        // ast-grep-ignore: kt-no-silent-result-collapse -- a file that vanished or cannot be read between the check and the read is left out, and its directory is still in looked_in
        val text = Cancellables.runCatchingCancellable { Files.readString(path) }.getOrNull() ?: return null
        return buildJsonObject {
            put("kind", kind)
            put("path", path.toString())
            put("head", head)
            put("text", text)
        }
    }

    private fun memoryFiles(dir: Path): List<Path> =
        // ast-grep-ignore: kt-no-silent-result-collapse -- no memory directory is no memory files; the directory is named in looked_in either way
        Cancellables.runCatchingCancellable { Files.newDirectoryStream(dir).use { it.toList() } }
            .getOrDefault(emptyList())
            .filter { it.fileName.toString().endsWith(MEMORY_SUFFIX) }
            .sorted()

    /** The heads' shared autoMemoryEnabled, or null when none states it or they disagree. */
    private fun autoMemory(): Boolean? = heads.values
        .mapNotNull { it.transcriptRoot?.resolve("settings.json") }
        .mapNotNull { settings ->
            // ast-grep-ignore: kt-no-silent-result-collapse -- a missing or unparseable settings file states nothing about the switch
            Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(settings)).jsonObject }
                .getOrNull()
                ?.get("autoMemoryEnabled")?.let { (it as? JsonPrimitive)?.booleanOrNull }
        }
        .distinct()
        .singleOrNull()

    /** One read of the registry, the teams and today's perf rows, shared by every row of a request. */
    private inner class ProjectView {
        private val records = registry?.read().orEmpty()
        private val allTeams = teams()?.teams().orEmpty()
        private val byRoot = records.mapNotNull { record -> repoOf(record)?.let { it.root to record } }
            .groupBy({ it.first }, { it.second })
        private val dayStart = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        private val today: List<Pair<SessionHead, SessionPerfWindow>> by lazy {
            heads.values.mapNotNull { head -> head.perfRows?.window(dayStart)?.let { head to it } }
        }
        private val table = compaction()
        val roots: List<String> =
            (byRoot.keys + allTeams.map { it.repo }.filter { it.isNotBlank() }).distinct().sorted()

        fun row(root: String): JsonObject {
            val sessions = byRoot[root].orEmpty()
            val repoTeams = allTeams.filter { it.repo == root }
            val held = repoTeams.flatMap { team -> team.slots.flatMap { it.sessionsHistory } }
            val tags = (sessions.mapNotNull { it.sessionId } + held).map { it.take(PERF_TAG) }.toSet()
            val tally = PerfTally()
            for ((head, window) in today) {
                window.rows.filter { it.session in tags }.forEach { tally.add(it, head.catalog) }
            }
            val touched = sessions.flatMap { listOfNotNull(it.updatedAt, it.statusUpdatedAt, it.startedAt) }
            val last = (touched + listOfNotNull(tally.lastAt)).maxOrNull()
            return buildJsonObject {
                put("id", root)
                put("root", root)
                put("live_sessions", sessions.count { it.availability == SessionAvailability.LIVE })
                put("teams", repoTeams.count { !it.archived })
                put("turns_today", tally.turns)
                put("cost_today_usd", tally.costUsd)
                put("day_start", dayStart)
                put("last_activity", last?.let(::JsonPrimitive) ?: JsonNull)
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
