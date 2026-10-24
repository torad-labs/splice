// NEW: Oct 13, 2026 (spec §9: computed values are inspectable; console audit gap 473, via splice-lead) — the context
// window Claude Code computed for one session, as its status line reported it to the head it runs on. The head already
// learns it (ClientWindows) and scales that session's counts against it; no route served it, so the console could
// not show the window a session actually runs in.
//
// A fifth SessionRowFacts member, by the four properties that file names: the daemon supplies it (each head's
// ClientWindows), features/sessions cannot reach it, it answers about ONE session, and it writes its own key.
package splice.sessions.http

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import splice.sessions.registry.SessionRecord

/** The window [SessionWindowOf.windowOf]'s session runs in, in tokens, as its client last reported it. */
public fun interface SessionWindowOf {
    /** The window, or null for a session whose client has not posted a status line to its head yet. */
    public fun windowOf(record: SessionRecord): Long?

    /** `client_context_window`, or nothing. */
    public fun write(record: SessionRecord, target: JsonObjectBuilder) {
        windowOf(record)?.let { target.put("client_context_window", it) }
    }
}

/** A control plane with no head windows wired: every row keeps its `client_context_window` absent. */
internal object NoSessionWindow : SessionWindowOf {
    override fun windowOf(record: SessionRecord): Long? = null
}
