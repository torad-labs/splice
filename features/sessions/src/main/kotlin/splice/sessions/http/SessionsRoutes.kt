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
//   route  `head` | `direct` | `unknown`: the registry's SessionRoute, decided where the process
//          environment is read. `head` itself is unchanged and still folds the last two into
//          "unknown head".
//   resumable  V4-421: whether a transcript with conversation bytes sits in some head's tree, which is
//          what GET /api/sessions/{id}/resume needs. A registry entry can exist with none (a
//          messaging bridge registers and never writes one). Left off a row when nothing was measured:
//          no id, no head tree, or the transcript view is off.
//   last   V4-444: the last main-thread message's role, tool, one-line redacted text and epoch-ms ts;
//          null without an available transcript message, session id, or enabled transcript view.
// and GET /api/sessions/{id}/transcript, one page through the injected SessionTranscripts port.
//
// WHICH TREES THE TRANSCRIPT ROUTE SEARCHES, in order: the head's own CLAUDE_CONFIG_DIR (the registry
// names the head of a session splice launched), the vanilla ~/.claude tree (sessions splice did not
// launch, and history written before V4-115 un-linked the trees), then every other head's tree. A
// session whose head is unknown starts at the vanilla tree. The page reports the path it read, and a
// miss reports every projects dir it searched.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.ConfigService
import splice.core.config.UserHome
import splice.http.JsonReply
import splice.sessions.query.SessionHead
import splice.sessions.registry.RepoOrigin
import splice.sessions.registry.RepoResolver
import splice.sessions.registry.RepoRoot
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.TrustedRoot
import splice.sessions.transcript.DEFAULT_TRANSCRIPT_PAGE
import splice.sessions.transcript.SKIPPED_SIDECHAIN
import splice.sessions.transcript.SKIPPED_UNPARSEABLE
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionActivity
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptPage
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal const val UNKNOWN_HEAD = "unknown head"
internal const val HEADLESS_NOTE = "headless `claude -p` runs never register; gone = the process exited; " +
    "stale = alive but no registry update inside the stale window"

public class SessionsRoutes(
    private val registry: SessionSource,
    private val transcripts: SessionTranscripts,
    private val heads: Map<String, SessionHead> = emptyMap(),
    private val config: ConfigService? = null,
    private val activity: ActivitySource = ActivitySource { null },
    /** The vanilla config root. Read only, never written (HEAD ISOLATION); a parameter so a test
     *  never reads the operator's own ~/.claude. */
    private val vanilla: Path = UserHome.dir().resolve(".claude"),
    /** V4-131: the team store the `team` key reads, per request. */
    private val teams: TeamSource = TeamSource { null },
    /** The session's own selected login, never the head-wide last selection. */
    private val accountOf: SessionAccountOf = SessionAccountOf { _, _ -> null },
    private val viewEnabled: SessionTranscriptViewEnabled = SessionTranscriptViewEnabled {
        config?.getConfig()?.transcriptView ?: true
    },
) {
    /** GET /api/sessions/{id}/edges and GET /api/sessions/edges. */
    public val edgeRoutes: ActivityRoutes = ActivityRoutes(registry, activity, SentTextSource(::sentTexts))

    /** One resolver per distinct root set: statuslineGitRoots is per-head overridable. */
    private val resolvers = ConcurrentHashMap<List<String>, RepoResolver>()

    /** V4-421: the head trees a resume searches, asked once per listing and held (ResumableSessions). */
    private val resumableSessions = ResumableSessions(
        transcripts,
        heads.values.mapNotNull { it.transcriptRoot }.distinct(),
    )

    public fun sessionsJson(): String = buildJsonObject {
        val listing = registry.list()
        val edges = edgeRoutes.index(listing.sessions)
        // The transcript-view switch is consulted before any reader opens a file, so off means no claim.
        val ids = listing.sessions.mapNotNull { it.sessionId }.toSet()
        val resumable = if (viewEnabled()) resumableSessions.among(ids) else Resumability(null)
        addEdgeState(this)
        put("note", HEADLESS_NOTE)
        // An unreadable directory is not an empty one: the error rides beside the (empty) list.
        listing.error?.let { put("error", it) }
        put("sessions", buildJsonArray { listing.sessions.forEach { add(row(it, edges, resumable)) } })
    }.toString()

    /** GET /api/sessions/{id}/transcript?cursor=&limit= */
    public fun transcript(sessionId: String, cursor: String?, limit: Int?): JsonReply {
        if (!viewEnabled()) return SessionTranscriptOff.reply
        val head = registry.read().firstOrNull { it.sessionId == sessionId }?.head
        return when (
            val lookup = transcripts.page(sessionId, treesFor(head), cursor, limit ?: DEFAULT_TRANSCRIPT_PAGE)
        ) {
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

    /** The exact same row projection for a durable-history entry after the live overlay. */
    public fun historyRow(record: SessionRecord): JsonObject = row(record, null, Resumability(null))

    private fun addEdgeState(body: JsonObjectBuilder) {
        val state = edgeRoutes.state() ?: return
        body.put("edges_state", state.wire)
        state.reason("edges")?.let { body.put("edges_reason", it) }
    }

    private fun row(s: SessionRecord, edges: EdgeIndex?, resumable: Resumability): JsonObject = buildJsonObject {
        put("pid", s.pid)
        put("session_id", s.sessionId)
        put("name", if (viewEnabled()) s.name else null)
        put("last", SessionActivity.last(s.sessionId, treesFor(s.head), transcripts, viewEnabled))
        put("kind", s.kind)
        put("version", s.version)
        put("cwd", s.cwd)
        put("status", s.status)
        put("status_updated_at", s.statusUpdatedAt)
        put("started_at", s.startedAt)
        put("updated_at", s.updatedAt)
        put("address", s.address)
        put("head", s.head ?: UNKNOWN_HEAD)
        put("route", routeName(s.route))
        put("availability", JsonPrimitive(s.availability.name.lowercase()))
        resumable.mark(this, s.sessionId)
        repoOf(s)?.let { put("repo", repoJson(it)) }
        put("team", s.sessionId?.let { teamOf(it) })
        put("account", s.sessionId?.let { accountOf.label(s.head, it) })
        val id = s.sessionId
        addEdgeState(this)
        if (edges != null && id != null) put("edges", edges.summary(id, s.address))
    }

    /** The wire name of the route the registry carried: `head` beside a real `head` key, `direct` for a
     *  session that never went through splice, `unknown` for one splice cannot place. `head` keeps
     *  printing "unknown head" for both of the last two; `route` is what tells them apart. */
    private fun routeName(route: SessionRoute): String = when (route) {
        is SessionRoute.Head -> "head"
        SessionRoute.Direct -> "direct"
        SessionRoute.Unknown -> "unknown"
    }

    /** V4-131: the session's repo as its row reports it (ProjectsRoutes groups by it); null without a cwd. */
    public fun repoOf(record: SessionRecord): RepoRoot? = record.cwd?.let { resolverFor(record.head).resolve(it) }

    /** The trusted root [head]'s statusline would probe [path] under, through the SAME per-head root
     *  set [repoOf] walks with (statuslineGitRoots is per-head overridable), or null outside all. */
    public fun statuslineRootOf(path: String, head: String?): TrustedRoot? = resolverFor(head).trustedRootOf(path)

    /** V4-131: a sender's SendMessage texts, from the transcript trees this route already searches. */
    public fun sentTexts(session: String, head: String?, ids: Set<String>): SentTexts {
        if (!viewEnabled()) return SentTexts(null, emptyMap(), ids)
        return transcripts.sentTexts(session, treesFor(head), ids)
    }

    private fun teamOf(session: String): String? =
        teams()?.bindingsOf(session)?.firstOrNull { (team, _) -> !team.archived }?.first?.id

    private fun repoJson(repo: RepoRoot) = buildJsonObject {
        put("root", repo.root)
        if (repo.reason == null) RepoOrigin.of(repo.root)?.let { put("remote", it) }
        repo.worktree?.let { put("worktree", it) }
        repo.reason?.let { put("reason", it) }
    }

    private fun resolverFor(head: String?): RepoResolver {
        val roots = config?.getConfig(head)?.statuslineGitRoots.orEmpty()
        return resolvers.computeIfAbsent(roots) { RepoResolver(it) }
    }

    /** The session's head tree, the vanilla tree, then every other head's (see the header). */
    private fun treesFor(head: String?): List<Path> {
        val own = head?.let { heads[it]?.transcriptRoot }
        val others = heads.values.mapNotNull { it.transcriptRoot }.filter { it != own }
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
