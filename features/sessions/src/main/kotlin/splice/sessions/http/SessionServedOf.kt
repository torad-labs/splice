// NEW: Oct 11, 2026 — the model a session is on, from what splice served it, so Continue on keeps its "from" model and
// leaves the current one out of its menu while reading Claude Code conversations is off (Marlin, rewalk 4).
//
// A fourth SessionRowFacts member, by the four properties that file names: the daemon supplies it (each head's newest
// perf row per session, and the moves Continue on kept), features/sessions cannot reach it, it answers about ONE
// session, and it writes its own key. It reads no conversation, so it holds whatever the reading switch says.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.sessions.registry.SessionRecord

/** The model splice last served [SessionServedOf.servedModel]'s session, or the model a later move put it on. */
public fun interface SessionServedOf {
    public fun servedModel(sessionId: String): String?

    /** `served_model`, or nothing. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        record.sessionId?.let(::servedModel)?.let { target.put("served_model", it) }
    }
}

/** A control plane with no perf rows wired: every row keeps its `served_model` absent. */
internal object NoSessionServed : SessionServedOf {
    override fun servedModel(sessionId: String): String? = null
}
