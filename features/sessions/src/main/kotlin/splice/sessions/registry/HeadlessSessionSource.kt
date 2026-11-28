package splice.sessions.registry

/** The serving launches in memory, before choosing one per session. */
public interface ServedLaunchRows {
    public fun rows(): List<SessionRecord>
}

/** Adds served launch rows to the listing only: they are not registrations that can be addressed or kept in history.
 *  A session the listing names as live keeps its own row. One the listing names only as ended or stale, and a launch
 *  now serves, shows the launch's live row in place of the old one: that session was resumed headless. */
public class HeadlessSessionSource(
    private val registry: SessionSource,
    private val served: ServedLaunchRows,
) : SessionSource by registry {
    override fun list(): SessionListing {
        val listed = registry.list()
        val launches = served.rows()
        if (launches.isEmpty()) return listed

        val live = listed.sessions.filter { it.availability == SessionAvailability.LIVE }
            .mapNotNullTo(HashSet(), SessionRecord::sessionId)
        val named = listed.sessions.mapNotNullTo(HashSet(), SessionRecord::sessionId)
        val headless = launches.filter { launch ->
            val id = launch.sessionId
            id !in named || (id !in live && launch.availability == SessionAvailability.LIVE)
        }.sortedByDescending { it.process.updatedAt }
        val resumed = headless.mapNotNullTo(HashSet()) { it.sessionId?.takeIf(named::contains) }
        return listed.copy(sessions = listed.sessions.filterNot { it.sessionId in resumed } + headless)
    }
}
