// NEW: V4-444 — what the sessions, teams and projects mounts share: the session reads built once over the
// registry, the heads as sessions see them, and the transcript history's index and roots. Split from
// SessionsMount so each capability's routes sit in their own mount and read the same instances.
package splice.app.control.mount

import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.SessionHeadAdapter
import splice.app.sources.PerfRowsFileSource
import splice.app.sources.PerfSessionAccountIndex
import splice.app.sources.PerfStatsSource
import splice.client.resume.ModelMoves
import splice.client.transcript.MovedTranscripts
import splice.client.transcript.TranscriptHistoryIndex
import splice.client.transcript.TranscriptReader
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.perf.KeptHistory
import splice.core.perf.OutcomeTag
import splice.core.process.LaunchOwners
import splice.core.topology.AuthKindRegistry
import splice.sessions.http.ActivitySource
import splice.sessions.http.ConfigSessionSettings
import splice.sessions.http.LaunchedTerminal
import splice.sessions.http.LaunchedTerminals
import splice.sessions.http.SessionAccountOf
import splice.sessions.http.SessionAccountState
import splice.sessions.http.SessionDrive
import splice.sessions.http.SessionEnding
import splice.sessions.http.SessionEndingOf
import splice.sessions.http.SessionRowFacts
import splice.sessions.http.SessionTurnCount
import splice.sessions.http.SessionTurnsOf
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.TeamSource
import splice.sessions.http.TerminalSource
import splice.sessions.http.TranscriptRoots
import splice.sessions.registry.RecordedSessions
import splice.sessions.registry.SEEN_SESSIONS_DIR
import splice.sessions.registry.SeenSessions
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionHistoryRoot
import java.util.concurrent.TimeUnit

/** The label a single-login head's one login is filed under: its requests' perf rows (TurnDriveFactory's fallback
 *  account label) and its quota (HeadQuotaPolling) both use it, and the Accounts roster shows that login as the head's
 *  single_login row. */
private const val SINGLE_LOGIN_LABEL = "primary"

/** [routes] is null when no session registry is wired, and every mount built over it then registers nothing.
 *  [ports] is read at CALL time: ControlPlane assigns the activity and team stores after construction. */
internal class SessionsWiring(
    sessions: SessionSource?,
    private val heads: Map<String, ManagedHead>,
    config: ConfigService,
    private val ports: ConsolePorts,
) {
    val sessionHeads = SessionHeadAdapter.adapt(heads)
    val historyIndex = TranscriptHistoryIndex()
    val historyRoots = listOf(SessionHistoryRoot(null, UserHome.claudeDir())) +
        sessionHeads.mapNotNull { (head, source) -> source.transcriptRoot?.let { SessionHistoryRoot(head, it) } }
    private val sessionAccounts = object : SessionAccountOf {
        override fun forRecords(records: List<SessionRecord>): SessionAccountOf {
            val saved = records.groupBy { it.head }.mapNotNull { (head, sessions) ->
                val managed = heads[head] ?: return@mapNotNull null
                if (managed.authSurface.authKind != CLIENT_AUTH_KIND) return@mapNotNull null
                val source = managed.sources.perfRows as? PerfRowsFileSource ?: return@mapNotNull null
                val ids = sessions.mapNotNull { it.sessionId }
                    .filterNot { ports.claudeLogins?.hasCarryingProof(requireNotNull(head), it) == true }.toSet()
                if (ids.isEmpty()) return@mapNotNull null
                requireNotNull(head) to source.sessionAccounts(ids)
            }.toMap()
            return snapshot(saved)
        }

        override fun label(head: String?, sessionId: String): String? =
            head?.let { account(it, sessionId, null) }

        override fun pin(head: String?, sessionId: String): String? = pinOf(head, sessionId)
    }

    /** Every head's per-session accumulator, summed, because a session that moved heads has rows on
     *  both and one head's count would read as the whole of it. The combined start is the LATEST of
     *  the heads that contributed (SessionTurnCount): the sum covers the session only where every one
     *  of those counters was already running when it began, so the conservative start is the honest
     *  one. A head with no counted row for the session contributes nothing and does not move the
     *  start, and no total anywhere leaves the row's `turns` absent rather than zero. */
    private val sessionTurns = SessionTurnsOf { id ->
        val counted = heads.values
            .mapNotNull { (it.sources.perf as? PerfStatsSource)?.sessionTotals }
            .mapNotNull { store -> store.totalFor(id) }
        if (counted.isEmpty()) {
            null
        } else {
            SessionTurnCount(
                turns = counted.sumOf { total -> total.models.values.sumOf { it.turns } },
                fromMs = counted.maxOf { it.fromMs },
            )
        }
    }

    /** How the session's newest request ended, newest across every head, and only when that ending holds the session
     *  back until something changes: a plan window or every account spent (At limit), or no credential (Signed
     *  out). A burst 429 passes on its own, so it is not one. */
    private val holdingEndings =
        setOf(OutcomeTag.PLAN_LIMIT, OutcomeTag.ALL_ACCOUNTS_EXHAUSTED, OutcomeTag.AUTH_MISSING)
            .mapTo(HashSet()) { it.wire }

    private val sessionEnding = SessionEndingOf { id ->
        heads.mapNotNull { (key, head) ->
            (head.sources.perf as? PerfStatsSource)?.sessionEndings?.endingFor(id)?.let { key to it }
        }
            .maxByOrNull { (_, ended) -> ended.ts }
            ?.takeIf { (_, ended) -> ended.outcome in holdingEndings }
            ?.let { (head, ended) ->
                val resetMs = ended.resetEpochSeconds?.let(TimeUnit.SECONDS::toMillis)
                // in the Accounts roster's words: a client head's row carries the login's identity, not its label
                val account = ended.account?.let { ports.claudeLogins?.accountLabel(head, it) ?: it }
                SessionEnding(ended.outcome, account, resetMs, ended.ts)
            }
    }

    /** The launch records splice-launch writes: a session started from the person's own tmux names its pane there. */
    private val launchOwners = LaunchOwners(StatePaths().stateDir)

    /** One session's terminal, by its id: say, answer, stop and its screen (Sessions). Read per call, the [ports] rule.
     *  A session splice did not open is reached through the pane its own launch recorded, while its process runs. */
    val drive = SessionDrive(
        TerminalSource { ports.sessionDriver },
        launched = LaunchedTerminals { id ->
            sessions?.read()
                ?.firstOrNull { it.sessionId == id && it.availability == SessionAvailability.LIVE }
                ?.process?.pid
                ?.let { pid ->
                    launchOwners.read(pid)?.takeIf { it.kind == "session" }?.terminal
                        ?.let { LaunchedTerminal(pid, it.pane, it.socket) }
                }
        },
    )

    /** The listing Sessions and Requests read: the registry, with the sessions Claude Code already forgot kept as
     *  ended under the one history window (SeenSessions). */
    private val listed = sessions?.let {
        // the daemon's own state root, never the process default: a daemon on another root keeps its record there
        val dir = config.statePaths.stateDir.resolve(SEEN_SESSIONS_DIR)
        RecordedSessions(it, SeenSessions(dir, KeptHistory { config.getConfig().historyWindow }))
    }
    val routes: SessionsRoutes? = listed?.let {
        SessionsRoutes(
            it,
            MovedTranscripts(TranscriptReader(), ModelMoves(StatePaths().modelMovesDir)),
            TranscriptRoots(sessionHeads),
            ConfigSessionSettings(config),
            ActivitySource { ports.activity },
            teams = TeamSource { ports.teams },
            facts = SessionRowFacts(accountOf = sessionAccounts, turnsOf = sessionTurns, endingOf = sessionEnding),
        )
    }

    /** The stable label of the login [session]'s requests on [head] carry, the one the Accounts roster and the perf
     *  rows use, so the console names it from the head plus this label. A client session reads the native place or
     *  added-account label proved by its own newest sent credential, or null when no login matches. Never another
     *  session's choice, the head's current selection, or a pool decision the request did not use. */
    private fun snapshot(saved: Map<String, PerfSessionAccountIndex.Snapshot>): SessionAccountOf =
        object : SessionAccountOf {
            override fun label(head: String?, sessionId: String): String? =
                head?.let { account(it, sessionId, saved[it]) }

            override fun pin(head: String?, sessionId: String): String? = pinOf(head, sessionId)

            override fun state(head: String?, sessionId: String): SessionAccountState = when {
                ports.claudeLogins?.hasCarryingProof(head.orEmpty(), sessionId) == true -> SessionAccountState.NONE
                saved[head]?.let { !it.complete || sessionId !in it.indexed } == true ->
                    SessionAccountState.HISTORY_LIMITED
                heads[head]?.authSurface?.authKind == CLIENT_AUTH_KIND && head !in saved ->
                    SessionAccountState.HISTORY_LIMITED
                else -> SessionAccountState.NONE
            }
        }

    /** The label this session is pinned to on its head's pool (its own pin, else the head's), or null. */
    private fun pinOf(head: String?, session: String): String? =
        head?.let { heads[it]?.authSurface?.accountPool?.view(session)?.pinnedLabel }

    private fun account(head: String, session: String, saved: PerfSessionAccountIndex.Snapshot?): String? {
        val managed = heads[head] ?: return null
        val pool = managed.authSurface.accountPool
        return when {
            managed.authSurface.authKind == CLIENT_AUTH_KIND -> clientAccount(head, session, saved)
            pool != null -> pool.view(session).selectedLabel
            AuthKindRegistry.isOAuth(managed.authSurface.authKind) -> SINGLE_LOGIN_LABEL
            else -> null
        }
    }

    private fun clientAccount(head: String, session: String, saved: PerfSessionAccountIndex.Snapshot?): String? {
        val logins = ports.claudeLogins
        val live = logins?.carryingAccount(head, session)
        if (live != null || logins?.hasCarryingProof(head, session) == true) return live
        val account = saved?.accounts?.get(session) ?: return null
        return logins?.accountLabel(head, account) ?: account
    }
}
