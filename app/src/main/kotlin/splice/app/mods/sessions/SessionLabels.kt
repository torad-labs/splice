package splice.app.mods.sessions

import splice.app.control.ConsolePorts
import splice.sessions.http.SessionLabelsOf
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord

/** Finds launch labels for registry rows, which do not carry launch metadata themselves. */
internal class SessionLabels(private val ports: ConsolePorts) : SessionLabelsOf {
    override fun labelsOf(record: SessionRecord): Map<String, String> {
        val pid = record.process.pid.takeUnless { record.availability == SessionAvailability.GONE }
        return ports.modLaunches?.forRecord(record.sessionId, pid)?.settings?.labels.orEmpty()
    }

    override fun forRecords(records: List<SessionRecord>): SessionLabelsOf {
        val labels = records.associateWith(::labelsOf)
        return SessionLabelsOf { labels[it].orEmpty() }
    }
}
