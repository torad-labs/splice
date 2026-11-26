package splice.app.mods.run

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.mods.sessions.ModLaunches
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource

internal interface ModSessions {
    fun list(mod: String): JsonArray
}

internal class ModCallRows(private val sessions: SessionSource, private val launches: ModLaunches) : ModSessions {
    override fun list(mod: String): JsonArray {
        val listing = sessions.list()
        if (listing.error != null) throw java.io.IOException("could not read the session registry")
        return JsonArray(listing.sessions.distinctBy(SessionRecord::sessionId).mapNotNull { row(it, mod) })
    }

    private fun row(record: SessionRecord, mod: String): JsonObject? {
        val id = record.sessionId?.takeUnless { ':' in it } ?: return null
        val pid = record.process.pid.takeUnless { record.availability == SessionAvailability.GONE }
        val settings = launches.forRecord(id, pid)?.settings
        if (settings?.modsOff?.suppresses(mod) == true) return null
        return buildJsonObject {
            put("id", id)
            put("kind", "session")
            put("title", record.name?.takeIf(String::isNotBlank) ?: id)
            put("status", status(record))
            if (settings != null) put("labels", buildJsonObject { settings.labels.forEach { (k, v) -> put(k, v) } })
        }
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
