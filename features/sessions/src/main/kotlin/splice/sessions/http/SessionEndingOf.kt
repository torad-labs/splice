// NEW: Oct 10, 2026 — step 3: a session whose newest request splice turned away at a limit, or for want of a
// credential, says so on its row, so Sessions and Teams can draw At limit and Signed out (fin's words, hitstop).
//
// A third SessionRowFacts member, by the four properties that file names: the daemon supplies it (each head's perf
// rows as they are appended), features/sessions cannot reach it, it answers about ONE session, and it writes its own
// key. Which endings hold a session back is the daemon's call (SessionsWiring); this only carries the one it hands.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.sessions.registry.SessionRecord

/** How the session's newest request ended, when that ending holds it back: the outcome tag, the account it was on,
 *  when the spent window comes back (null when the provider named none), when it ended, and the head that turned it
 *  away (null when unknown). */
public data class SessionEnding(
    val outcome: String,
    val account: String?,
    val resetMs: Long?,
    val atMs: Long,
    val head: String? = null,
)

/** The daemon's newest ending per session, read per row. Null = nothing holds the session back. */
public fun interface SessionEndingOf {
    public fun endingOf(sessionId: String): SessionEnding?

    /** `ended_by`, or nothing. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        val ending = record.sessionId?.let { endingOf(it) } ?: return
        // a head's limit holds the session only while it runs there: moved onto another command, it is that one's
        if (record.head != (ending.head ?: record.head)) return
        target.put(
            "ended_by",
            buildJsonObject {
                put("outcome", ending.outcome)
                ending.account?.let { put("account", it) }
                ending.resetMs?.let { put("reset_ms", it) }
                put("at_ms", ending.atMs)
            },
        )
    }
}

/** A control plane with no perf rows wired: every row keeps its `ended_by` absent. */
internal object NoSessionEnding : SessionEndingOf {
    override fun endingOf(sessionId: String): SessionEnding? = null
}
