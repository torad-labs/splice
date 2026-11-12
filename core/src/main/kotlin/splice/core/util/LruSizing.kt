// NEW: the LinkedHashMap sizing every access-ordered LRU in this daemon was spelling for itself.
// Three bounded maps — AccountPool's per-session account pins, LoginSessions' tracked attempts and
// StatuslineRateLimits' per-head windows — independently declared the same pair of numbers, which
// const-single-source correctly read as a COPY rather than three decisions. One home, one import.
package splice.core.util

/** The two arguments an access-ordered `LinkedHashMap(initialCapacity, loadFactor, true)` takes. */
public object LruSizing {
    // why: java.util.HashMap's own documented defaults. These maps are bounded by their own eviction
    // rule, not by capacity, so the table only needs a starting size that does not immediately
    // rehash — restating the JDK's choice is the honest one rather than inventing a number.
    public const val INITIAL_CAPACITY: Int = 16

    // why: the JDK default too, and the one that pairs with the capacity above — changing either
    // alone would rehash at a different fill than HashMap is tuned for.
    public const val LOAD_FACTOR: Float = 0.75f
}
