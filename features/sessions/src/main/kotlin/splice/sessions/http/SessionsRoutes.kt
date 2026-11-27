// NEW: v0.4.0 FEATURES.md §4 — `/api/sessions` — the registry as JSON, read on every request
// (Claude Code rewrites the files as sessions come and go). Read-only: no socket is ever opened.
//
// V4-130 (FEATURES.md 4.4, 6) adds, per row:
//   repo   the git root of the row's cwd (RepoResolver: trusted roots only, worktrees folded into their
//          shared repo, an outside cwd reported as itself). The key is left off a row
//          with no cwd, because the console types it optional and non-nullable.
//   team   the id of the first unarchived team the session is bound in, by created time then id
//          (V4-131, TeamStore.bindingsOf), or null when it is bound in none or the team store is
//          unwired. Always present, so the console groups every row.
//   route  `head` | `door` | `direct` | `unknown`: the registry's SessionRoute, decided where the launch record and
//          the process environment are read. `head` folds the last two into "unknown head", and reads `door` on a
//          door row, whose turns run on whichever head their model names.
//   door   on a `door` row only (door-sessions, Nov 20, 2026), as `repo` is left off others: the command its launch
//          recorded, the door's address it runs on, and the machine that serves it (null for this machine's door).
//   last   V4-444: the last main-thread message's role, tool, one-line redacted text and epoch-ms ts;
//          null without an available transcript message, session id, or enabled transcript view.
//   turns  how many turns the session has run, from the daemon's per-session accumulator, with
//          `turns_partial` saying whether the count is a floor (SessionTurnsOf, which says why an absent
//          `turns` is not a zero). Both keys are left off together when nothing counted the session.
// and GET /api/sessions/{id}/transcript, one page through the injected SessionTranscripts port.
//
// WHICH TREES THE TRANSCRIPT ROUTE SEARCHES, in order: the head's own CLAUDE_CONFIG_DIR (the registry
// names the head of a session splice launched), the vanilla ~/.claude tree (sessions splice did not
// launch, and history written before V4-115 un-linked the trees), then every other head's tree. A
// session whose head is unknown starts at the vanilla tree. The page reports the path it read, and a
// miss says so.
package splice.sessions.http

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.http.JsonReply
import splice.sessions.http.transcript.SessionTranscriptReply
import splice.sessions.listing.GoneSessionRows
import splice.sessions.listing.ListedRow
import splice.sessions.note.PeerVersions
import splice.sessions.projects.SessionProjects
import splice.sessions.registry.RepoResolver
import splice.sessions.registry.RepoRoot
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionRouteWire
import splice.sessions.registry.SessionSource
import splice.sessions.teams.Team
import splice.sessions.teams.TeamStore
import splice.sessions.transcript.RowTranscriptFields
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionActivity
import splice.sessions.transcript.SessionTitleOf
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptFields
import java.util.concurrent.ConcurrentHashMap

internal const val UNKNOWN_HEAD = "unknown head"

/** The team each session is bound to, with the store read once for a listing. Asked once a row it cost a look at the
 *  store's file and a sort of every team for each of 3,201 rows (Nov 12, 2026). A session bound in several teams
 *  reports the earliest created that is not archived, ties broken by id. */
private class BoundTeams(store: TeamStore?) {
    private val open: List<Team> by lazy {
        store?.teams().orEmpty().filterNot { it.archived }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    fun of(session: String): Team? = open.firstOrNull { team -> team.slots.any { it.session == session } }
}

public class SessionsRoutes(
    private val registry: SessionSource,
    private val transcripts: SessionTranscripts,
    private val roots: TranscriptRoots = TranscriptRoots(),
    settings: SessionSettings = NoSessionSettings,
    private val activity: ActivitySource = ActivitySource { null },
    /** V4-131: the team store the `team` key reads, per request. */
    private val teams: TeamSource = TeamSource { null },
    /** The daemon-supplied per-session facts each row carries, each writing its own keys: the session's
     *  own proved login and its turn count (SessionRowFacts, which says why they travel as one). */
    private val facts: SessionRowFacts = SessionRowFacts(),
) {
    /** What Settings > Your data lists and deletes of the activity stores. */
    public val edgeRoutes: ActivityRoutes = ActivityRoutes(activity)

    /** The settings every row asks, read once for a listing ([ListingSettings]). */
    private val asks = ListingSettings(settings)

    /** One resolver per distinct root set: statuslineGitRoots is per-head overridable. */
    private val resolvers = ConcurrentHashMap<List<String>, RepoResolver>()

    /** Which project a session, a working directory or a request belongs to: the one answer every screen shares. */
    public val projects: SessionProjects = SessionProjects(asks)

    private val viewEnabled = SessionTranscriptViewEnabled(asks::transcriptView)

    /** A session with no name is called by the first line of its first message; the request rows ask here too. */
    public val titleOf: SessionTitleOf = SessionTitleOf { record ->
        record.sessionId?.takeIf { viewEnabled() && record.name.isNullOrBlank() }
            ?.let { transcripts.title(it, roots.treesFor(record.head), record.process.cwd) }
    }

    /** Each row's last, model and title, kept against its transcript file's stamp. */
    private val transcriptFields = RowTranscriptFields(transcripts, viewEnabled, titleOf)
    private val transcriptReply = SessionTranscriptReply(transcripts, roots::treesFor)

    /** [held] is the mark of the gone sessions' rows the reader already holds, from an earlier answer's `gone_mark`. */
    public fun sessionsJson(held: String? = null): String = asks.listing().use { listingJson(held) }

    private fun listingJson(held: String?): String = buildJsonObject {
        val listing = registry.list()
        val sessions = listing.bySession()
        val rowFacts = facts.forRecords(sessions)
        val bound = BoundTeams(teams())
        // whether splice reads the conversations: off, the console shows no question a session waits on
        put("reading", viewEnabled())
        // The versions among these sessions a note is sent to, by the peer wire rule (peer messaging 1.4), not a fixed list.
        val noteVersions = listing.sessions.filter { PeerVersions.refusal(it.client) == null }
            .mapNotNull { it.client.version }.distinct().sorted()
        put("note_versions", buildJsonArray { noteVersions.forEach { add(JsonPrimitive(it)) } })
        // An unreadable directory is not an empty one: the error rides beside the (empty) list.
        listing.error?.let { put("error", it) }
        val rows = GoneSessionRows(
            sessions.map { record ->
                val gone = record.availability == SessionAvailability.GONE
                val updatedAt = record.process.updatedAt ?: record.status.updatedAt ?: 0L
                ListedRow(gone, updatedAt) { row(record, rowFacts, bound) }
            },
        )
        // A reader that holds the gone sessions' rows under this mark is sent the sessions still here alone.
        put("gone_mark", rows.mark)
        if (rows.heldBy(held)) put("gone_held", true)
        // The gone sessions older than the newest sent: GET /api/sessions/history pages and searches them.
        if (rows.older > 0) put("gone_older", rows.older)
        put("sessions", buildJsonArray { rows.owed(held).forEach { add(it) } })
    }.toString()

    /** GET /api/sessions/{id}/transcript?cursor=&limit= reads forward from the start. `before=` reads the newest messages
     *  instead: `end`, or the `earlier` cursor the page before handed back (V4-444: a session opens at its newest).
     *  `cursor=after:<index>` reads what follows the message with that index, which it reads again, numbered alike. */
    public fun transcript(sessionId: String, cursor: String?, limit: Int?, before: String? = null): JsonReply {
        if (!viewEnabled()) return SessionTranscriptOff.reply
        val record = registry.read().firstOrNull { it.sessionId == sessionId }
        return transcriptReply.read(sessionId, record, cursor, limit, before)
    }

    /** The exact same row projection for a durable-history entry after the live overlay. */
    public fun historyRow(record: SessionRecord): JsonObject = row(record)

    /** History pages snapshot only their selected records, never shared mutable per-listing state. */
    public val historyRows: SessionHistoryRowOf = object : SessionHistoryRowOf {
        override fun invoke(record: SessionRecord): JsonObject = historyRow(record)
        override fun forRecords(records: List<SessionRecord>): SessionHistoryRowOf {
            val snapped = facts.forRecords(records)
            return SessionHistoryRowOf { row(it, snapped) }
        }
    }

    private fun row(
        s: SessionRecord,
        rowFacts: SessionRowFacts = facts,
        teamOf: BoundTeams = BoundTeams(teams()),
    ): JsonObject = buildJsonObject {
        put("session_id", s.sessionId)
        put("name", if (viewEnabled()) s.name else null)
        val headless = s.client.launchLabels != null
        val shown = if (headless) {
            TranscriptFields(JsonNull, JsonNull, null)
        } else {
            transcriptFields.of(s, roots.treesFor(s.head))
        }
        put("last", shown.last)
        put("model", shown.model)
        put("title", shown.title)
        put("version", s.client.version)
        put("cwd", s.process.cwd)
        put("status", s.status.state)
        put("waiting_for", s.status.waitingFor)
        put("status_updated_at", s.status.updatedAt)
        put("started_at", s.process.startedAt)
        put("updated_at", s.process.updatedAt)
        put("address", s.address)
        put("head", s.launchedOn ?: UNKNOWN_HEAD)
        put("route", SessionRouteWire.name(s.route))
        (s.route as? SessionRoute.Door)?.let { put("door", SessionRouteWire.door(it.door)) }
        put("availability", JsonPrimitive(s.availability.name.lowercase()))
        repoOf(s)?.let { put("repo", repoJson(it)) }
        projects.put(this, s)
        // the card names the team a person reads, never its id; no reader joins a row to its team by id
        put("team_name", s.sessionId?.let(teamOf::of)?.name)
        rowFacts.write(s, this)
    }

    /** The model [record] runs on as its row reports it: what Continue on keeps as the model a move left. With
     *  reading off its row reads no model, and what splice served it stands in. */
    public fun modelOf(record: SessionRecord): String? = SessionActivity.model(
        record.sessionId,
        record.process.cwd,
        roots.treesFor(record.head),
        transcripts,
        viewEnabled,
    ).let(JsonScalars::str) ?: record.sessionId?.let(facts.servedOf::servedModel)

    /** V4-131: the session's repo as its row reports it (ProjectsRoutes groups by it); null without a cwd. */
    public fun repoOf(record: SessionRecord): RepoRoot? =
        record.process.cwd?.takeIf { record.client.launchLabels == null }?.let { resolverFor(record.head).resolve(it) }

    /** V4-131: a sender's SendMessage texts, from the transcript trees this route already searches. */
    public fun sentTexts(session: String, head: String?, ids: Set<String>): SentTexts {
        if (!viewEnabled()) return SentTexts(null, emptyMap(), ids)
        return transcripts.sentTexts(session, roots.treesFor(head), ids)
    }

    private fun repoJson(repo: RepoRoot) = buildJsonObject {
        put("root", repo.root)
        repo.worktree?.let { put("worktree", it) }
        // why the folder gets no project (an untrusted or outside root), so Sessions can say it under No project
        repo.reason?.let { put("reason", it) }
        repo.gap?.let { put("reason_code", it.wire) }
    }

    private fun resolverFor(head: String?): RepoResolver {
        return resolvers.computeIfAbsent(asks.gitRoots(head)) { RepoResolver(it) }
    }
}
