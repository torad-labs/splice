// NEW: V4-444 — what the sessions, teams and projects mounts share: the session reads built once over the
// registry, the heads as sessions see them, and the transcript history's index and roots. Split from
// SessionsMount so each capability's routes sit in their own mount and read the same instances.
package splice.app.control.mount

import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.SessionHeadAdapter
import splice.app.sources.PerfRowsFileSource
import splice.app.sources.PerfSessionAccountIndex
import splice.client.transcript.TranscriptHistoryIndex
import splice.client.transcript.TranscriptReader
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.config.ConfigService
import splice.core.config.UserHome
import splice.core.topology.AuthKindRegistry
import splice.sessions.http.ActivitySource
import splice.sessions.http.SessionAccountOf
import splice.sessions.http.SessionAccountState
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.TeamSource
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionHistoryRoot

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
    val historyRoots = listOf(SessionHistoryRoot(null, UserHome.dir().resolve(".claude"))) +
        sessionHeads.mapNotNull { (head, source) -> source.transcriptRoot?.let { SessionHistoryRoot(head, it) } }
    private val sessionAccounts = object : SessionAccountOf {
        override fun forRecords(records: List<SessionRecord>): SessionAccountOf {
            val saved = records.groupBy { it.head }.mapNotNull { (head, sessions) ->
                val managed = heads[head] ?: return@mapNotNull null
                if (managed.authKind != CLIENT_AUTH_KIND) return@mapNotNull null
                val source = managed.perfRows as? PerfRowsFileSource ?: return@mapNotNull null
                val ids = sessions.mapNotNull { it.sessionId }
                    .filterNot { ports.claudeLogins?.hasCarryingProof(requireNotNull(head), it) == true }.toSet()
                if (ids.isEmpty()) return@mapNotNull null
                requireNotNull(head) to source.sessionAccounts(ids)
            }.toMap()
            return snapshot(saved)
        }

        override fun label(head: String?, sessionId: String): String? =
            head?.let { account(it, sessionId, null) }
    }
    val routes: SessionsRoutes? = sessions?.let {
        SessionsRoutes(
            it,
            TranscriptReader(),
            sessionHeads,
            config,
            ActivitySource { ports.activity },
            teams = TeamSource { ports.teams },
            accountOf = sessionAccounts,
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

            override fun state(head: String?, sessionId: String): SessionAccountState = when {
                ports.claudeLogins?.hasCarryingProof(head.orEmpty(), sessionId) == true -> SessionAccountState.NONE
                saved[head]?.let { !it.complete || sessionId !in it.indexed } == true ->
                    SessionAccountState.HISTORY_LIMITED
                heads[head]?.authKind == CLIENT_AUTH_KIND && head !in saved -> SessionAccountState.HISTORY_LIMITED
                else -> SessionAccountState.NONE
            }
        }

    private fun account(head: String, session: String, saved: PerfSessionAccountIndex.Snapshot?): String? {
        val managed = heads[head] ?: return null
        val pool = managed.accountPool
        return when {
            managed.authKind == CLIENT_AUTH_KIND -> clientAccount(head, session, saved)
            pool != null -> pool.view(session).selectedLabel
            AuthKindRegistry.isOAuth(managed.authKind) -> SINGLE_LOGIN_LABEL
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
