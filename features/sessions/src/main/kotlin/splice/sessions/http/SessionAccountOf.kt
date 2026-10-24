// NEW: request-owned session attribution separates bounded history from proved absence.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.sessions.registry.SessionRecord

/** The session's own proved login, never another session's or a head-wide selection. */
public fun interface SessionAccountOf {
    public fun label(head: String?, sessionId: String): String?

    /** The account this session is pinned to, by its own pin or else the head's; null when nothing is pinned. */
    public fun pin(head: String?, sessionId: String): String? = null

    /** The head-wide pin on the session's head, so a reader can tell the session's own pin from the head's. */
    public fun headPin(head: String?): String? = null

    /** The session's own last account switch on its head's pool, never the head's or another session's. */
    public fun switchOf(head: String?, sessionId: String): SessionAccountSwitch? = null

    /** A request-owned snapshot prepares one bounded lookup per head, before row serialization. */
    public fun forRecords(records: List<SessionRecord>): SessionAccountOf = this

    /** One label lookup per row. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        val id = record.sessionId
        target.put("account", id?.let { label(record.head, it) })
        target.put("account_pin", id?.let { pin(record.head, it) })
        target.put("account_pin_head", headPin(record.head))
        id?.let { switchOf(record.head, it) }?.let { switch ->
            target.putJsonObject("account_switch") {
                put("from", switch.from)
                put("to", switch.to)
                put("reason", switch.reason)
                put("at_epoch_millis", switch.atEpochMillis)
            }
        }
    }
}

/** One session's last move between logins on its head's pool, and splice's reason for it (spec section 11). */
public data class SessionAccountSwitch(
    val from: String,
    val to: String,
    val reason: String,
    val atEpochMillis: Long,
)
