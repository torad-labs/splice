package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.sessions.registry.ServedSessionIndex
import splice.sessions.registry.SessionRecord

/** The labels a session was launched with (`SPLICE_LABEL_*`), or none. A listing asks [forRecords] once, so one
 *  snapshot serves every row. */
public fun interface SessionLabelsOf {
    public fun labelsOf(record: SessionRecord): Map<String, String>

    /** One lookup for [records], taken before the first row is written. */
    public fun forRecords(records: List<SessionRecord>): SessionLabelsOf = this

    /** `labels`, always present, so a row with none reads `{}` and not a missing key. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        target.putJsonObject("labels") {
            labelsOf(record).forEach { (key, value) -> put(key, value) }
        }
    }
}

/** A control plane with no launch labels wired: every row carries an empty `labels`. */
internal object NoSessionLabels : SessionLabelsOf {
    override fun labelsOf(record: SessionRecord): Map<String, String> = emptyMap()
}

/** Labels from the served-session index, snapshotted once per listing. */
public class ServedLabels(private val served: ServedSessionIndex) : SessionLabelsOf {
    override fun labelsOf(record: SessionRecord): Map<String, String> =
        record.sessionId?.let { served()?.labelsOf(it) }.orEmpty()

    override fun forRecords(records: List<SessionRecord>): SessionLabelsOf {
        val index = served()
        val snap = records.associate { it.sessionId to index?.labelsOf(it.sessionId).orEmpty() }
        return SessionLabelsOf { snap[it.sessionId].orEmpty() }
    }
}
