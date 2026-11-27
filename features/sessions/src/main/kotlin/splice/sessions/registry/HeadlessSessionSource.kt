package splice.sessions.registry

/** The serving launches in memory, before choosing one per session. */
public interface ServedLaunchRows {
    public fun rows(): List<SessionRecord>
}

/** Adds served launch rows to the listing only: they are not registrations that can be addressed or kept in history. */
public class HeadlessSessionSource(
    private val registry: SessionSource,
    private val served: ServedLaunchRows,
) : SessionSource by registry {
    override fun list(): SessionListing {
        val listed = registry.list()
        val launches = served.rows()
        if (launches.isEmpty()) return listed

        val registered = listed.sessions.mapNotNullTo(HashSet(), SessionRecord::sessionId)
        val headless = launches.filterNot { it.sessionId in registered }
            .sortedByDescending { it.process.updatedAt }
        return listed.copy(sessions = listed.sessions + headless)
    }
}
