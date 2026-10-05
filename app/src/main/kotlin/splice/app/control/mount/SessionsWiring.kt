// NEW: V4-444 — what the sessions, teams and projects mounts share: the session reads built once over the
// registry, the heads as sessions see them, and the transcript history's index and roots. Split from
// SessionsMount so each capability's routes sit in their own mount and read the same instances.
package splice.app.control.mount

import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.SessionHeadAdapter
import splice.client.transcript.TranscriptHistoryIndex
import splice.client.transcript.TranscriptReader
import splice.core.auth.CLIENT_AUTH_KIND
import splice.core.config.ConfigService
import splice.core.config.UserHome
import splice.core.topology.AuthKindRegistry
import splice.sessions.http.ActivitySource
import splice.sessions.http.SessionAccountOf
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.TeamSource
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
    private val sessionAccounts = SessionAccountOf { head, id -> head?.let { account(it, id) } }
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
    private fun account(head: String, session: String): String? {
        val managed = heads[head] ?: return null
        val pool = managed.accountPool
        return when {
            managed.authKind == CLIENT_AUTH_KIND -> ports.claudeLogins?.carryingAccount(head, session)
            pool != null -> pool.view(session).selectedLabel
            AuthKindRegistry.isOAuth(managed.authKind) -> SINGLE_LOGIN_LABEL
            else -> null
        }
    }
}
