// NEW: V4-363 keeps project roots consistent with durable Sessions history and the git resolver.
package splice.sessions.http

import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionHistorySource
import splice.sessions.transcript.SessionTranscriptViewEnabled

/** A transcript-only session stays visible after its registration exits; paging is for the UI. */
public class ProjectSessions(
    private val registry: SessionSource,
    private val history: SessionHistorySource,
    private val roots: List<SessionHistoryRoot>,
    private val viewEnabled: SessionTranscriptViewEnabled = SessionTranscriptViewEnabled { true },
) : SessionSource {
    override fun read(): List<SessionRecord> = list().sessions

    override fun list(): SessionListing {
        val live = registry.list()
        if (!viewEnabled()) return live
        val joined = linkedMapOf<String, JoinedSession>()
        history.scan(roots).sessions.forEach { entry -> joined[entry.sessionId] = JoinedSession(entry, null) }
        live.sessions.forEach { record ->
            val id = record.sessionId ?: return@forEach
            val previous = joined[id]
            if (previous?.live == null) joined[id] = JoinedSession(previous?.entry, record)
        }
        return SessionListing(
            live.sessions.filter { it.sessionId == null } + joined.mapNotNull { (id, item) -> item.item(id)?.record },
            live.error,
        )
    }
}

/** A session outside a trusted git checkout has a cwd, not a project root. */
internal class ProjectRoots(private val repoOf: RepoOf) {
    fun grouped(records: List<SessionRecord>): Map<String, List<SessionRecord>> = records
        .mapNotNull { record -> repoOf(record)?.takeIf { it.reason == null }?.let { it.root to record } }
        .groupBy({ it.first }, { it.second })
}
