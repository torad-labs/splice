package splice.core.model

import splice.core.util.LruSizing

/** The input buckets Claude Code copies from a reply into its statusline post. */
public data class ClientInput(val uncached: Long, val cached: Long, val written: Long) {
    public val total: Long get() = uncached + cached + written

    public fun scaled(sizing: UsageSizing): ClientInput =
        ClientInput(sizing.scale(uncached), sizing.scale(cached), sizing.scale(written))
}

/** The original context behind a set of emitted buckets, before scaling and rounding. */
public data class ActualContext(val input: Long, val window: Long)

/** Recent replies in this daemon only. An unrecognized or ambiguous post is displayed as sent. */
public class ClientUsage internal constructor(private val capacity: Int) {
    private data class Key(val session: String, val reported: ClientInput)

    private val replies =
        object : LinkedHashMap<Key, ActualContext?>(LruSizing.INITIAL_CAPACITY, LruSizing.LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ActualContext?>?): Boolean =
                size > capacity
        }

    public fun record(session: String, reported: ClientInput, actual: ActualContext) {
        synchronized(replies) {
            val key = Key(session, reported)
            replies[key] = if (!replies.containsKey(key) || replies[key] == actual) actual else null
        }
    }

    public fun context(session: String?, reported: ClientInput): ActualContext? =
        session?.let { synchronized(replies) { replies[Key(it, reported)] } }
}
