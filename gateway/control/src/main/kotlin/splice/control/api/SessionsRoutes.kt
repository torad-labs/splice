// NEW: v0.4.0 FEATURES.md §4 — `/api/sessions` — the registry as JSON, read on every request
// (Claude Code rewrites the files as sessions come and go). Read-only: no socket is ever opened.
//
// V4-130 (FEATURES.md 4.4, 6) adds, per row:
//   repo   the git root of the row's cwd (RepoResolver: trusted roots only, worktrees folded into their
//          shared repo, an outside cwd reported as itself with the reason). The key is left off a row
//          with no cwd, because the console types it optional and non-nullable.
//   team   always null in this row. Teams are V4-131's (the daemon's team store and its slot binding);
//          the key is present so the console groups every row, and becomes the bound team id there.
//   edges  `{sent, received, last_at}` from the message edge store (ActivityRoutes), left off every
//          row when the stores are unwired, never reported as zero sends nobody watched.
// and GET /api/sessions/{id}/transcript, one page of the session's transcript (TranscriptReader).
//
// WHICH TREES THE TRANSCRIPT ROUTE SEARCHES, in order: the head's own CLAUDE_CONFIG_DIR (the registry
// names the head of a session splice launched), the vanilla ~/.claude tree (sessions splice did not
// launch, and history written before V4-115 un-linked the trees), then every other head's tree. A
// session whose head is unknown starts at the vanilla tree. The page reports the path it read, and a
// miss reports every projects dir it searched.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.control.ManagedHead
import splice.core.config.ConfigService
import splice.core.sessions.DEFAULT_TRANSCRIPT_PAGE
import splice.core.sessions.RepoResolver
import splice.core.sessions.RepoRoot
import splice.core.sessions.SKIPPED_SIDECHAIN
import splice.core.sessions.SKIPPED_UNPARSEABLE
import splice.core.sessions.SessionRecord
import splice.core.sessions.SessionRegistry
import splice.core.sessions.TranscriptLookup
import splice.core.sessions.TranscriptPage
import splice.core.sessions.TranscriptReader
import splice.core.sessions.TranscriptTrees
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

internal const val UNKNOWN_HEAD = "unknown head"
internal const val HEADLESS_NOTE = "headless `claude -p` runs never register; gone = the process exited; " +
    "stale = alive but no registry update inside the stale window"

/** A status and a JSON body, for routes that answer more than 200. */
public data class JsonReply(val status: HttpStatusCode, val body: String) {
    public suspend fun send(call: ApplicationCall) {
        call.respondText(body, ContentType.Application.Json, status)
    }
}

public class SessionsRoutes(
    private val registry: SessionRegistry,
    private val heads: Map<String, ManagedHead> = emptyMap(),
    private val config: ConfigService? = null,
    private val activity: ActivitySource = ActivitySource { null },
    /** The vanilla config root. Read only, never written (HEAD ISOLATION); a parameter so a test
     *  never reads the operator's own ~/.claude. */
    private val vanilla: Path = Paths.get(System.getProperty("user.home"), ".claude"),
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
        s.cwd?.let { put("repo", repoJson(resolverFor(s.head).resolve(it))) }
        put("team", JsonNull)
        val id = s.sessionId
        if (edges != null && id != null) put("edges", edges.summary(id, s.address))
    }

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
