// NEW: request-owned session attribution separates bounded history from proved absence.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.sessions.registry.SessionRecord

/** Attribution absence distinguishes complete recorded history from a bounded or unreadable seed. */
public enum class SessionAccountState(public val wire: String) {
    KNOWN("known"),
    NONE("none"),
    HISTORY_LIMITED("history_limited"),
}

/** The session's own proved login, never another session's or a head-wide selection. */
public fun interface SessionAccountOf {
    public fun label(head: String?, sessionId: String): String?

    /** A request-owned snapshot prepares one bounded lookup per head, before row serialization. */
    public fun forRecords(records: List<SessionRecord>): SessionAccountOf = this

    /** Called only for an absent label. Sources without bounded history keep their original absence. */
    public fun state(head: String?, sessionId: String): SessionAccountState = SessionAccountState.NONE

    /** One label lookup per row. A positive label itself supplies its known state. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        val id = record.sessionId
        val account = id?.let { label(record.head, it) }
        target.put("account", account)
        if (id != null) {
            val state = if (account != null) SessionAccountState.KNOWN else state(record.head, id)
            target.put("account_state", state.wire)
        }
    }
}
