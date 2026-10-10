// NEW: Oct 10, 2026 — the session listing with the sessions Claude Code has already forgotten put back as ended.
package splice.sessions.registry

/** [source]'s listing, every session it names noted in [seen], and every recorded session it no longer names
 *  added as GONE: a card to open on Sessions' Ended fold, and a name for Requests' rows. A recorded session carries
 *  only what the record keeps, so its status and client are unknown and it has no process to reach. */
public class RecordedSessions(
    private val source: SessionSource,
    private val seen: SeenSessions,
) : SessionSource {
    override fun read(): List<SessionRecord> = list().sessions

    override fun list(): SessionListing {
        val listing = source.list()
        seen.note(listing.sessions)
        val present = listing.sessions.mapNotNullTo(HashSet()) { it.sessionId }
        return listing.copy(sessions = listing.sessions + seen.absent(present).map(::ended))
    }

    private fun ended(kept: SeenSession) = SessionRecord(
        sessionId = kept.sessionId,
        name = kept.name,
        status = SessionStatus(),
        route = kept.head?.let { SessionRoute.Head(it) } ?: SessionRoute.Unknown,
        availability = SessionAvailability.GONE,
        process = SessionProcess(
            pid = null,
            cwd = kept.cwd,
            startedAt = null,
            updatedAt = kept.lastActivityMs,
            messagingSocketPath = null,
        ),
        client = SessionClient(kind = null, version = null),
    )
}
