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
//   route  `head` | `direct` | `unknown`: the registry's SessionRoute, decided where the process
//          environment is read. `head` itself is unchanged and still folds the last two into
//          "unknown head".
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

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.http.JsonReply
import splice.sessions.note.PeerVersions
import splice.sessions.projects.SessionProjects
import splice.sessions.registry.RepoResolver
import splice.sessions.registry.RepoRoot
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.teams.Team
import splice.sessions.transcript.DEFAULT_TRANSCRIPT_PAGE
import splice.sessions.transcript.ModelMove
import splice.sessions.transcript.RowTranscriptFields
import splice.sessions.transcript.SKIPPED_UNPARSEABLE
import splice.sessions.transcript.SentTexts
import splice.sessions.transcript.SessionActivity
import splice.sessions.transcript.SessionTitleOf
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.SessionTranscripts
import splice.sessions.transcript.TranscriptLookup
import splice.sessions.transcript.TranscriptMessage
import splice.sessions.transcript.TranscriptPage
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal const val UNKNOWN_HEAD = "unknown head"

/** One transcript message as the route sends it. */
private object MessageWire {
    fun json(m: TranscriptMessage): JsonObject = buildJsonObject {
        put("index", m.index)
        put("role", m.role.name.lowercase())
        m.ts?.let { put("ts", it) }
        put("text", m.text)
        m.toolUse.name?.let { put("tool", it) }
        m.toolUse.id?.let { put("tool_use_id", it) }
        m.source.kind?.let { put("kind", it) }
        m.source.from?.let { put("from", it) }
    }
}

private object MoveWire {
    fun json(move: ModelMove): JsonObject = buildJsonObject {
        put("model", move.model)
        move.command?.let { put("command", it) }
        put("moved_at", move.movedAt)
        move.fromCommand?.let { put("from_command", it) }
        move.fromModel?.let { put("from_model", it) }
        put("thinking_dropped", move.thinkingDropped)
    }
}

/** `before=end` on the transcript route: read from the last message. */
private const val FROM_END = "end"

// why: before=at:<epoch ms> opens the page ending at that moment: a request's door to its place in a conversation.
private const val AT_MOMENT = "at:"

// why: cursor=after:<index> reads from where the message with that index starts to the end of the file: a live pane's
// five-second poll asks only for its last message, which may have grown, and what came after it, a few KB, where a read
// from the end sent its whole page again.
private const val AFTER_MESSAGE = "after:"

/** One transcript read: forward from [cursor], back from [before] (or the end), the page ending at `at:<ms>`, or what
 *  follows the message a cursor `after:<index>` names. [cwd] is the session's working directory as its row reads it,
 *  so a session with no transcript yet is judged missing where its row judged it, without a walk on every read. */
private class TranscriptAsk(
    val trees: List<Path>,
    val cursor: String?,
    val size: Int,
    val before: String?,
    val cwd: String?,
) {
    /** The message index an `after:` cursor names; null for every other read. */
    val after: Long? = cursor?.takeIf { before == null && it.startsWith(AFTER_MESSAGE) }
        ?.removePrefix(AFTER_MESSAGE)?.toLongOrNull()?.takeIf { it >= 0 }

    fun lookup(transcripts: SessionTranscripts, sessionId: String): TranscriptLookup = when {
        after != null -> transcripts.pageAfter(sessionId, trees, after, size, cwd)
        cursor?.startsWith(AFTER_MESSAGE) == true -> TranscriptLookup.Refused("not a message this daemon numbered")
        before == null -> transcripts.page(sessionId, trees, cursor, size, cwd)
        before.startsWith(AT_MOMENT) -> before.removePrefix(AT_MOMENT).toLongOrNull()
            ?.let { transcripts.pageAt(sessionId, trees, it, size, cwd) }
            ?: TranscriptLookup.Refused("not a moment this daemon reads")
        else -> transcripts.pageBefore(sessionId, trees, before.takeUnless { it == FROM_END }, size, cwd)
    }
}

public class SessionsRoutes(
    private val registry: SessionSource,
    private val transcripts: SessionTranscripts,
    private val roots: TranscriptRoots = TranscriptRoots(),
    private val settings: SessionSettings = NoSessionSettings,
    private val activity: ActivitySource = ActivitySource { null },
    /** V4-131: the team store the `team` key reads, per request. */
    private val teams: TeamSource = TeamSource { null },
    /** The daemon-supplied per-session facts each row carries, each writing its own keys: the session's
     *  own proved login and its turn count (SessionRowFacts, which says why they travel as one). */
    private val facts: SessionRowFacts = SessionRowFacts(),
) {
    /** What Settings > Your data lists and deletes of the activity stores. */
    public val edgeRoutes: ActivityRoutes = ActivityRoutes(activity)

    /** One resolver per distinct root set: statuslineGitRoots is per-head overridable. */
    private val resolvers = ConcurrentHashMap<List<String>, RepoResolver>()

    /** Which project a session, a working directory or a request belongs to: the one answer every screen shares. */
    public val projects: SessionProjects = SessionProjects(settings)

    private val viewEnabled = SessionTranscriptViewEnabled(settings::transcriptView)

    /** A session with no name is called by the first line of its first message; the request rows ask here too. */
    public val titleOf: SessionTitleOf = SessionTitleOf { record ->
        record.sessionId?.takeIf { viewEnabled() && record.name.isNullOrBlank() }
            ?.let { transcripts.title(it, roots.treesFor(record.head), record.process.cwd) }
    }

    /** Each row's last, model and title, kept against its transcript file's stamp. */
    private val transcriptFields = RowTranscriptFields(transcripts, viewEnabled, titleOf)

    public fun sessionsJson(): String = buildJsonObject {
        val listing = registry.list()
        val rowFacts = facts.forRecords(listing.sessions)
        // whether splice reads the conversations: off, the console shows no question a session waits on (Marlin, p180)
        put("reading", viewEnabled())
        // The versions among these sessions a note is sent to, by the peer wire rule (peer messaging 1.4), not a fixed list.
        val noteVersions = listing.sessions.filter { PeerVersions.refusal(it.client) == null }
            .mapNotNull { it.client.version }.distinct().sorted()
        put("note_versions", buildJsonArray { noteVersions.forEach { add(JsonPrimitive(it)) } })
        // An unreadable directory is not an empty one: the error rides beside the (empty) list.
        listing.error?.let { put("error", it) }
        put(
            "sessions",
            buildJsonArray { listing.sessions.forEach { add(row(it, rowFacts)) } },
        )
    }.toString()

    /** GET /api/sessions/{id}/transcript?cursor=&limit= reads forward from the start. `before=` reads the newest messages
     *  instead: `end`, or the `earlier` cursor the page before handed back (V4-444: a session opens at its newest).
     *  `cursor=after:<index>` reads what follows the message with that index, which it reads again, numbered alike. */
    public fun transcript(sessionId: String, cursor: String?, limit: Int?, before: String? = null): JsonReply {
        if (!viewEnabled()) return SessionTranscriptOff.reply
        val record = registry.read().firstOrNull { it.sessionId == sessionId }
        val trees = roots.treesFor(record?.head)
        val size = limit ?: DEFAULT_TRANSCRIPT_PAGE
        val ask = TranscriptAsk(trees, cursor, size, before, record?.process?.cwd)
        return when (val lookup = ask.lookup(transcripts, sessionId)) {
            is TranscriptLookup.Found ->
                JsonReply(HttpStatusCode.OK, pageJson(lookup.page, before != null, ask.after != null))
            TranscriptLookup.Missing -> JsonReply(
                HttpStatusCode.NotFound,
                buildJsonObject { put("error", "no transcript for this session id") }.toString(),
            )
            is TranscriptLookup.Refused -> JsonReply(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", lookup.reason) }.toString(),
            )
        }
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
    ): JsonObject = buildJsonObject {
        put("session_id", s.sessionId)
        put("name", if (viewEnabled()) s.name else null)
        val trees = roots.treesFor(s.head)
        val shown = transcriptFields.of(s, trees)
        put("last", shown.last)
        put("model", shown.model)
        put("title", shown.title)
        put("version", s.client.version)
        // a mirror's session runs on another machine: its machine is named, and nothing of a folder here is
        put("cwd", s.process.cwd.takeIf { s.machine == null })
        s.machine?.let { put("machine", it) }
        put("status", s.status.state)
        put("waiting_for", s.status.waitingFor)
        put("status_updated_at", s.status.updatedAt)
        put("started_at", s.process.startedAt)
        put("updated_at", s.process.updatedAt)
        put("address", s.address)
        put("head", s.head ?: UNKNOWN_HEAD)
        put("route", routeName(s.route))
        put("availability", JsonPrimitive(s.availability.name.lowercase()))
        if (s.machine == null) {
            repoOf(s)?.let { put("repo", repoJson(it)) }
            projects.put(this, s)
        }
        val bound = s.sessionId?.let { teamOf(it) }
        // the card names the team a person reads, never its id; no reader joins a row to its team by id
        put("team_name", bound?.name)
        rowFacts.write(s, this)
    }

    /** The wire name of the route the registry carried: `head` beside a real `head` key, `direct` for a
     *  session that never went through splice, `unknown` for one splice cannot place. `head` keeps
     *  printing "unknown head" for both of the last two; `route` is what tells them apart. */
    private fun routeName(route: SessionRoute): String = when (route) {
        is SessionRoute.Head -> "head"
        SessionRoute.Direct -> "direct"
        SessionRoute.Unknown -> "unknown"
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
        record.process.cwd?.let { resolverFor(record.head).resolve(it) }

    /** V4-131: a sender's SendMessage texts, from the transcript trees this route already searches. */
    public fun sentTexts(session: String, head: String?, ids: Set<String>): SentTexts {
        if (!viewEnabled()) return SentTexts(null, emptyMap(), ids)
        return transcripts.sentTexts(session, roots.treesFor(head), ids)
    }

    private fun teamOf(session: String): Team? =
        teams()?.bindingsOf(session)?.firstOrNull { (team, _) -> !team.archived }?.first

    private fun repoJson(repo: RepoRoot) = buildJsonObject {
        put("root", repo.root)
        repo.worktree?.let { put("worktree", it) }
        // why the folder gets no project (an untrusted or outside root), so Sessions can say it under No project
        repo.reason?.let { put("reason", it) }
    }

    private fun resolverFor(head: String?): RepoResolver {
        return resolvers.computeIfAbsent(settings.gitRoots(head)) { RepoResolver(it) }
    }

    private fun pageJson(page: TranscriptPage, fromEnd: Boolean, after: Boolean): String = buildJsonObject {
        put("messages", buildJsonArray { page.messages.forEach { add(MessageWire.json(it)) } })
        put("path", page.path) // the transcript file this page was read from, which the pane names under its log
        if (fromEnd) put("earlier", page.earlier)
        // more came after the message than one page holds: the pane reads from the end instead
        if (after) put("more", page.next != null)
        put("moves", buildJsonArray { transcripts.moves(page.sessionId).forEach { add(MoveWire.json(it)) } })
        // a move that did not come back leaves no mark, but splice ended the client for it: when he pressed
        put("unmoved", buildJsonArray { transcripts.unmoved(page.sessionId).forEach { add(JsonPrimitive(it)) } })
        // the lines this page could not read: Sessions counts them into what it says it could not show
        put("unparseable_lines", page.skipped[SKIPPED_UNPARSEABLE] ?: 0)
    }.toString()
}
