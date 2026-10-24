// NEW: Oct 12, 2026 — whether message edges are being saved, asked at every write, so Settings' "Save who messaged
// whom" takes effect on the next message and no restart (S9 #13). The window they are kept for is a RetentionDays.
package splice.sessions.activity

import splice.core.storage.RetentionDays

/** True while a new message edge is to be saved; what is already on disk stays until its window or a delete. */
public fun interface EdgesStored {
    public fun on(): Boolean
}

/** The two settings the edge store follows while the daemon runs: how many days it keeps and whether it saves. */
public data class LiveEdges(public val days: RetentionDays, public val stored: EdgesStored)
