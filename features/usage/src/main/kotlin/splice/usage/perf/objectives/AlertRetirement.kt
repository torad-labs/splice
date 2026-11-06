package splice.usage.perf.objectives

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Removed/revised rules are retired with explicit uncertainty, never a fabricated healthy resolution. */
internal object AlertRetirement {
    fun removeMissing(data: AlertJournalData, settings: ObjectiveSettings, now: Long): AlertJournalData {
        val removed = data.states.keys - settings.rules.map { it.id }.toSet()
        return removed.fold(data) { current, id -> retire(current, id, now, "rule_removed") }
    }

    fun changed(data: AlertJournalData, id: String, revision: String, now: Long): AlertJournalData =
        if (data.states[id]?.revision?.let { it != revision } == true) {
            retire(
                data,
                id,
                now,
                "rule_revision_changed",
            )
        } else {
            data
        }

    private fun retire(data: AlertJournalData, id: String, now: Long, reason: String): AlertJournalData {
        val previous = data.latest[id]
        val events = if (previous == null) {
            data.events
        } else {
            data.events + JsonObject(
                previous + buildJsonObject {
                    put("state", "insufficient_data")
                    put("active", false)
                    put("retired", true)
                    put("reason", reason)
                    put("as_of_ms", now)
                    put("delivery_status", "disabled")
                    put("dedup_key", ObjectiveJson.hash("${ObjectiveJson.text(previous, "alert_id")}:$reason"))
                },
            )
        }
        return data.copy(states = data.states - id, latest = data.latest - id, events = events)
    }
}
