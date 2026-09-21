// NEW: v0.4.0 FEATURES.md §4 — `/api/sessions` — the registry as JSON, read on every request
// (Claude Code rewrites the files as sessions come and go). Read-only: no socket is ever opened.
//
// V4-130 (FEATURES.md 4.4, 6) adds, per row:
//   repo   the git root of the row's cwd (RepoResolver: trusted roots only, worktrees folded into their
//          shared repo, an outside cwd reported as itself with the reason). The key is left off a row
//          with no cwd, because the console types it optional and non-nullable.
//   team   the id of the first unarchived team the session is bound in, by created time then id
//          (V4-131, TeamStore.bindingsOf), or null when it is bound in none or the team store is
//          unwired. Always present, so the console groups every row.
//   edges  `{sent, received, last_at}` from the message edge store (ActivityRoutes), left off every
//          row when the stores are unwired, never reported as zero sends nobody watched.
// and GET /api/sessions/{id}/transcript, one page of the session's transcript (TranscriptReader).
//
// WHICH TREES THE TRANSCRIPT ROUTE SEARCHES, in order: the head's own CLAUDE_CONFIG_DIR (the registry
// names the head of a session splice launched), the vanilla ~/.claude tree (sessions splice did not
// launch, and history written before V4-115 un-linked the trees), then every other head's tree. A
// session whose head is unknown starts at the vanilla tree. The page reports the path it read, and a
// miss reports every projects dir it searched.
package splice.control.api.sessions

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.transcript.DEFAULT_TRANSCRIPT_PAGE
import splice.client.transcript.SKIPPED_SIDECHAIN
import splice.client.transcript.SKIPPED_UNPARSEABLE
import splice.client.transcript.SentTexts
import splice.client.transcript.TranscriptLookup
import splice.client.transcript.TranscriptPage
import splice.client.transcript.TranscriptReader
import splice.client.transcript.TranscriptTrees
import splice.control.ManagedHead
import splice.core.config.ConfigService
import splice.core.sessions.RepoResolver
import splice.core.sessions.RepoRoot
import splice.core.sessions.SessionRecord
import splice.core.sessions.SessionRegistry
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

internal const val UNKNOWN_HEAD = "unknown head"
internal const val HEADLESS_NOTE = "headless `claude -p` runs never register; gone = the process exited; " +
    "stale = alive but no registry update inside the stale window"

/** A status and a JSON body, for routes that answer more than 200. */
internal data class JsonReply(val status: HttpStatusCode, val body: String) {
    public suspend fun send(call: ApplicationCall) {
        call.respondText(body, ContentType.Application.Json, status)
    }
}

internal class SessionsRoutes(
    private val registry: SessionRegistry,
    private val heads: Map<String, ManagedHead> = emptyMap(),
    private val config: ConfigService? = null,
    private val activity: ActivitySource = ActivitySource { null },
    /** The vanilla config root. Read only, never written (HEAD ISOLATION); a parameter so a test
     *  never reads the operator's own ~/.claude. */
    private val vanilla: Path = Paths.get(System.getProperty("user.home"), ".claude"),
    /** V4-131: the team store the `team` key reads, per request. */
    private val teams: TeamSource = TeamSource { null },
) {
    /** GET /api/sessions/{id}/edges and GET /api/sessions/edges. */
    public val edgeRoutes: ActivityRoutes = ActivityRoutes(registry, activity)
    private val transcripts = TranscriptReader(TranscriptTrees(::treesFor))

    /** One resolver per distinct root set: statuslineGitRoots is per-head overridable. */
    private val resolvers = ConcurrentHashMap<List<String>, RepoResolver>()

    public fun sessionsJson(): String = buildJsonObject {
        val listing = registry.list()
        val edges = edgeRoutes.index(listing.sessions)
        put("note", HEADLESS_NOTE)
        // An unreadable directory is not an empty one: the error rides beside the (empty) list.
        listing.error?.let { put("error", it) }
        put("sessions", buildJsonArray { listing.sessions.forEach { add(row(it, edges)) } })
    }.toString()

    /** GET /api/sessions/{id}/transcript?cursor=&limit= */
    public fun transcript(sessionId: String, cursor: String?, limit: Int?): JsonReply {
        val head = registry.read().firstOrNull { it.sessionId == sessionId }?.head
        return when (val lookup = transcripts.page(sessionId, head, cursor, limit ?: DEFAULT_TRANSCRIPT_PAGE)) {
            is TranscriptLookup.Found -> JsonReply(HttpStatusCode.OK, pageJson(lookup.page))
            is TranscriptLookup.Missing -> JsonReply(
                HttpStatusCode.NotFound,
                buildJsonObject {
                    put("error", "no transcript for this session id")
                    put("searched", buildJsonArray { lookup.searched.forEach { add(JsonPrimitive(it)) } })
                }.toString(),
            )
            is TranscriptLookup.Refused -> JsonReply(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", lookup.reason) }.toString(),
            )
        }
    }

    private fun row(s: SessionRecord, edges: EdgeIndex?) = buildJsonObject {
        put("pid", s.pid)
        put("session_id", s.sessionId)
        put("name", s.name)
        put("kind", s.kind)
        put("version", s.version)
        put("cwd", s.cwd)
        put("status", s.status)
        put("status_updated_at", s.statusUpdatedAt)
        put("started_at", s.startedAt)
        put("updated_at", s.updatedAt)
        put("address", s.address)
        put("head", s.head ?: UNKNOWN_HEAD)
        put("availability", JsonPrimitive(s.availability.name.lowercase()))
        repoOf(s)?.let { put("repo", repoJson(it)) }
        put("team", s.sessionId?.let { teamOf(it) })
        val id = s.sessionId
        if (edges != null && id != null) put("edges", edges.summary(id, s.address))
    }

    /** V4-131: the session's repo as its row reports it (ProjectsRoutes groups by it); null without a cwd. */
    internal fun repoOf(record: SessionRecord): RepoRoot? = record.cwd?.let { resolverFor(record.head).resolve(it) }

    /** V4-131: a sender's SendMessage texts, from the transcript trees this route already searches. */
    internal fun sentTexts(session: String, head: String?, ids: Set<String>): SentTexts =
        transcripts.sentTexts(session, head, ids)

    private fun teamOf(session: String): String? =
        teams()?.bindingsOf(session)?.firstOrNull { (team, _) -> !team.archived }?.first?.id

    private fun repoJson(repo: RepoRoot) = buildJsonObject {
        put("root", repo.root)
        repo.worktree?.let { put("worktree", it) }
        repo.reason?.let { put("reason", it) }
    }

    private fun resolverFor(head: String?): RepoResolver {
        val roots = config?.getConfig(head)?.statuslineGitRoots.orEmpty()
        return resolvers.computeIfAbsent(roots) { RepoResolver(it) }
    }

    /** The session's head tree, the vanilla tree, then every other head's (see the header). */
    private fun treesFor(head: String?): List<Path> {
        val own = head?.let { heads[it]?.launchSpec?.trees?.own }
        val others = heads.values.mapNotNull { it.launchSpec?.trees?.own }.filter { it != own }
        // listOf(vanilla), never `+ vanilla`: a Path is an Iterable of its own name elements, so
        // List<Path> + Path appends each component as a relative path of its own.
        return (listOfNotNull(own) + listOf(vanilla) + others).distinct()
    }

    private fun pageJson(page: TranscriptPage): String = buildJsonObject {
        put("session_id", page.sessionId)
        put("path", page.path)
        put(
            "messages",
            buildJsonArray {
                page.messages.forEach { m ->
                    add(
                        buildJsonObject {
                            put("index", m.index)
                            put("role", m.role.name.lowercase())
                            m.ts?.let { put("ts", it) }
                            put("text", m.text)
                            m.tool?.let { put("tool", it) }
                            m.result?.let { put("result", it) }
                        },
                    )
                }
            },
        )
        put("next", page.next)
        // Declared additions (V4-130, routed to splice-design): the page's denominator. What it read
        // past, by kind, with the two kinds the orchestrator asked for named on their own.
        put("unparseable_lines", page.skipped[SKIPPED_UNPARSEABLE] ?: 0)
        put("sidechain_records", page.skipped[SKIPPED_SIDECHAIN] ?: 0)
        put(
            "skipped_records",
            buildJsonObject {
                page.skipped.filterKeys { it != SKIPPED_UNPARSEABLE && it != SKIPPED_SIDECHAIN }
                    .forEach { (kind, n) -> put(kind, n) }
            },
        )
    }.toString()
}
