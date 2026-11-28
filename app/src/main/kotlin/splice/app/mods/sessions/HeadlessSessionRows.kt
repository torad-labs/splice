package splice.app.mods.sessions

import splice.app.control.ConsolePorts
import splice.sessions.registry.ServedLaunchRows
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionStatus
import java.time.Instant
import java.time.format.DateTimeParseException

internal class HeadlessSessionRows(private val ports: ConsolePorts) : ServedLaunchRows {
    override fun rows(): List<SessionRecord> {
        val launches = ports.modLaunches?.servingLaunches().orEmpty()
        val processes = HashMap<String, SessionAvailability>()
        return launches.flatMap { (id, owners) ->
            owners.map { record ->
                val availability = processes.getOrPut(record.launch.owner) {
                    if (record.isAlive()) SessionAvailability.LIVE else SessionAvailability.GONE
                }
                row(id, record, availability)
            }
        }
    }

    private fun row(id: String, record: StoredModLaunch, availability: SessionAvailability): SessionRecord {
        val launch = record.launch
        return SessionRecord(
            sessionId = id,
            name = null,
            status = SessionStatus(),
            route = SessionRoute.Unknown,
            availability = availability,
            process = SessionProcess(launch.pid, launch.cwd, startedAt(record), record.recordedAt, null),
            client = SessionClient(kind = "headless", version = null, launchLabels = launch.settings.labels),
        )
    }

    private fun startedAt(record: StoredModLaunch): Long? = try {
        record.processStart?.let { Instant.parse(it).toEpochMilli() }
    } catch (_: DateTimeParseException) {
        null
    }
}
