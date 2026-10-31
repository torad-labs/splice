// NEW: Oct 10, 2026 — the session listing with the sessions Claude Code has already forgotten put back as ended.
package splice.sessions.registry

import splice.core.config.StatePaths
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.core.util.EnvReader

/** [source]'s listing, every session it names noted in [seen], and every recorded session it no longer names
 *  added as GONE: a card to open on Sessions' Ended fold, and a name for Requests' rows. A recorded session carries
 *  only what the record keeps, so its status and client are unknown and it has no process to reach.
 *
 *  A live session no launch joins to a head takes the head [seen] recorded for it, so the head it joined by its
 *  requests survives a restart (Eli's Reading, §4, Oct 11). That is one with no launch record, and one whose record
 *  no longer matches (its head was removed, or another head owns its port now): its requests are the evidence left. */
public class RecordedSessions(
    private val source: SessionSource,
    private val seen: SeenSessions,
) : SessionSource {
    override fun read(): List<SessionRecord> = list().sessions

    override fun list(): SessionListing {
        val listing = source.list().let { it.copy(sessions = it.sessions.map(::joined)) }
        seen.note(listing.sessions)
        val present = listing.sessions.mapNotNullTo(HashSet()) { it.sessionId }
        return listing.copy(sessions = listing.sessions + seen.absent(present).map(seen::ended))
    }

    private fun joined(record: SessionRecord): SessionRecord {
        val head = record.sessionId?.takeIf { record.wantsHead() }?.let(seen::headOf) ?: return record
        return record.copy(route = SessionRoute.Head(head))
    }
}

/** The head whose port [sessionId]'s requests arrive on, as this daemon has seen them since it started, or null when
 *  none has. A turn names its session and the head that served it, so this needs no launch record. */
public fun interface HeadOfSession {
    public operator fun invoke(sessionId: String): String?
}

/** [source]'s listing with every live session no launch joins to a head joined to the head its requests arrive on
 *  ([arrivals]). A session its launch record joins is never moved by this. One whose record no longer matches (its
 *  head was removed, or another head owns the declared port) has no join, so its requests decide like any other.
 *  One with no join and no request since the upgrade keeps the route [source] gave it (Eli's Reading, §4, Oct 11). */
public class ArrivalJoinedSessions(
    private val source: SessionSource,
    private val arrivals: HeadOfSession,
) : SessionSource {
    override fun read(): List<SessionRecord> = list().sessions

    override fun list(): SessionListing {
        val listing = source.list()
        return listing.copy(sessions = listing.sessions.map(::joined))
    }

    private fun joined(record: SessionRecord): SessionRecord {
        val head = record.sessionId?.takeIf { record.wantsHead() }?.let(arrivals::invoke) ?: return record
        return record.copy(route = SessionRoute.Head(head))
    }
}

/** [ArrivalJoinedSessions] over the heads the daemon recorded under the state directory [env] names, whole history
 *  kept: the listing the console draws, for the command line to print the same head. */
internal class SeenArrivalSessions(source: SessionSource, env: EnvReader) : SessionSource by ArrivalJoinedSessions(
    source,
    HeadOfSession { session ->
        val dir = StatePaths(envReader = env).stateDir.resolve(SEEN_SESSIONS_DIR)
        SeenSessions(dir, KeptHistory { HistoryWindow(days = null) }).headOf(session)
    },
)
