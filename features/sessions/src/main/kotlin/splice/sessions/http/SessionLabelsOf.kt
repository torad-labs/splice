package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.sessions.registry.SessionRecord

/** Labels from the latest launch serving this session, or an empty map if there are none. */
public fun interface SessionLabelsOf {
    public fun labelsOf(record: SessionRecord): Map<String, String>

    public fun forRecords(records: List<SessionRecord>): SessionLabelsOf = this

    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        target.putJsonObject("labels") {
            labelsOf(record).forEach { (key, value) -> put(key, value) }
        }
    }
}

internal object NoSessionLabels : SessionLabelsOf {
    override fun labelsOf(record: SessionRecord): Map<String, String> = emptyMap()
}
