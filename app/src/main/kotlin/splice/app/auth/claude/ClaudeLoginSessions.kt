// NEW: preserve ClaudeLogins' refusal of live or unreadable sessions before native replacement.
package splice.app.auth.claude

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.client.HeadSessions
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource

internal class ClaudeLoginSessions(private val registry: SessionSource) {
    fun read(location: ClaudeLoginLocation): HeadSessions {
        val head = location.target.head.key
        val listing = registry.list()
        listing.error?.let { return HeadSessions.Unreadable(it) }
        val live = listing.sessions.filter { session ->
            session.availability != SessionAvailability.GONE &&
                (
                    session.head == head || session.route == SessionRoute.Unknown ||
                        (location.id == ClaudeLoginPlaceId.NATIVE && session.route == SessionRoute.Direct)
                    )
        }
        return HeadSessions.Read(live.map { "a running Claude session, pid ${it.process.pid}" })
    }
}
