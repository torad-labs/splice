package splice.app.mods.run

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.mods.events.ModEventText
import splice.app.mods.sessions.ModLaunches
import splice.app.mods.sessions.ModLiveSession
import splice.app.mods.sessions.ModSessionTracker
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource

internal interface ModSessions {
    fun list(mod: String): JsonArray
}

internal class ModCallRows(
    private val sessions: SessionSource,
    private val launches: ModLaunches,
    private val text: ModEventText = ModEventText(emptyMap()),
    private val tracked: ModSessionTracker? = null,
) : ModSessions {
    override fun list(mod: String): JsonArray {
        val listing = sessions.list()
        if (listing.error != null) throw java.io.IOException("could not read the session registry")
        val active = tracked?.active().orEmpty()
        val registered = listing.sessions.distinctBy(SessionRecord::sessionId)
        val registeredIds = registered.mapNotNull(SessionRecord::sessionId).toHashSet()
        val headless = active.mapNotNull { (id, row) ->
            if (id in registeredIds || row.off?.suppresses(mod) == true) null else row(id, id, "working", row.labels)
        }
        return JsonArray(registered.mapNotNull { row(it, mod, active) } + headless)
    }

    private fun row(record: SessionRecord, mod: String, active: Map<String, ModLiveSession>): JsonObject? {
        val id = record.sessionId?.takeIf { ':' !in it && text.safe(it) } ?: return null
        val live = active[id]
        val settings = if (live == null) settings(record, id) else null
        if ((live?.off ?: settings?.modsOff)?.suppresses(mod) == true) return null
        return row(id, title(record, id), status(record), live?.labels ?: settings?.labels?.let { text.labels(it) })
    }

    private fun title(record: SessionRecord, id: String): String =
        record.name?.takeIf { it.isNotBlank() && text.safe(it) } ?: id

    private fun settings(record: SessionRecord, id: String): splice.launch.LaunchModSettings? {
        val pid = record.process.pid.takeUnless { record.availability == SessionAvailability.GONE }
        return launches.forRecord(id, pid)?.settings
    }

    private fun row(id: String, title: String, status: String, labels: JsonObject?): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", "session")
        put("title", title)
        put("status", status)
        if (labels != null) put("labels", labels)
    }

    private fun status(record: SessionRecord): String = when {
        record.availability == SessionAvailability.GONE -> "done"
        record.availability == SessionAvailability.STALE -> "paused"
        record.status.state == "failed" -> "failed"
        record.status.waitingFor != null || record.status.state == "waiting" -> "waiting"
        record.status.state in setOf("working", "busy") -> "working"
        else -> "paused"
    }
}

internal class ModCalls {
    @Volatile
    var sessions: ModSessions? = null

    fun reply(mod: String, id: Long, method: String?): JsonObject {
        if (method != "sessions.list") return error(id, "This mod host request is not served.")
        val source = sessions ?: return error(id, "The session registry is not available.")
        val rows = source.list(mod)
        return buildJsonObject {
            put("type", "reply")
            put("id", id)
            put("rows", rows)
        }
    }

    fun error(id: Long, message: String): JsonObject = buildJsonObject {
        put("type", "reply")
        put("id", id)
        put("error", message)
    }
}
