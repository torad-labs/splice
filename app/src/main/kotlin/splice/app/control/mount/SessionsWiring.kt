// NEW: V4-444 — what the sessions, teams and projects mounts share: the session reads built once over the
// registry, the heads as sessions see them, and the transcript history's index and roots. Split from
// SessionsMount so each capability's routes sit in their own mount and read the same instances.
package splice.app.control.mount

import splice.app.control.ConsolePorts
import splice.app.control.ManagedHead
import splice.app.control.SessionHeadAdapter
import splice.client.transcript.TranscriptHistoryIndex
import splice.client.transcript.TranscriptReader
import splice.core.config.ConfigService
import splice.core.config.UserHome
import splice.core.topology.AuthKindRegistry
import splice.sessions.http.ActivitySource
import splice.sessions.http.SessionAccountOf
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.TeamSource
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionHistoryRoot

/** [routes] is null when no session registry is wired, and every mount built over it then registers nothing.
 *  [ports] is read at CALL time: ControlPlane assigns the activity and team stores after construction. */
internal class SessionsWiring(
    sessions: SessionSource?,
    heads: Map<String, ManagedHead>,
    config: ConfigService,
    ports: ConsolePorts,
) {
    val sessionHeads = SessionHeadAdapter.adapt(heads)
    val historyIndex = TranscriptHistoryIndex()
    val historyRoots = listOf(SessionHistoryRoot(null, UserHome.dir().resolve(".claude"))) +
        sessionHeads.mapNotNull { (head, source) -> source.transcriptRoot?.let { SessionHistoryRoot(head, it) } }
    private val sessionAccounts = SessionAccountOf { head, id ->
        val managed = head?.let(heads::get)
        val pool = managed?.accountPool
        when {
            pool != null -> pool.view(id).selectedLabel
            managed != null && AuthKindRegistry.isOAuth(managed.authKind) -> "Only login on $head"
            else -> null
        }
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
}
