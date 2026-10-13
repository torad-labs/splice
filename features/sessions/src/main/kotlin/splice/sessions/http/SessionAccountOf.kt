// NEW: request-owned session attribution separates bounded history from proved absence.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.sessions.registry.SessionRecord

/** The session's own proved login, never another session's or a head-wide selection. */
public fun interface SessionAccountOf {
    public fun label(head: String?, sessionId: String): String?

    /** The account this session is pinned to, by its own pin or else the head's; null when nothing is pinned. */
    public fun pin(head: String?, sessionId: String): String? = null

    /** A request-owned snapshot prepares one bounded lookup per head, before row serialization. */
    public fun forRecords(records: List<SessionRecord>): SessionAccountOf = this

    /** One label lookup per row. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        val id = record.sessionId
        target.put("account", id?.let { label(record.head, it) })
        target.put("account_pin", id?.let { pin(record.head, it) })
    }
}
