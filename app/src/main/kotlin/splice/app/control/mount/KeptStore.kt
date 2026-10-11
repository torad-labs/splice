// NEW: Oct 11, 2026 — one kept store of the user's conversation, as Settings > Your data draws it: what it holds now,
// and the one call that clears it. The three stores that were counted nowhere (transcript copies, compaction
// summaries, code mode work) each implement this, and one pair of routes serves them all.
package splice.app.control.mount

/** What a store holds: files and their bytes, and when the oldest was last written, or null when it holds none. */
internal data class StoreHeld(val files: Long, val bytes: Long, val oldestMs: Long?)

internal interface KeptStore {
    /** What the store holds now. Throws when it cannot be read, so a count is never taken from a part of it. */
    fun held(): StoreHeld

    /** Deletes everything the store holds. Returns null when it is clear, or the first failure's words. */
    fun clear(): String?
}
