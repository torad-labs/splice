// NEW: LAYOUT-01 — the head facts the turns feature's console routes consume: the compaction stats
// read and the model catalog. The control plane adapts its wider ManagedHead into this projection, so
// the turns feature never depends upward on the control plane.
package splice.head

import splice.core.model.ModelCatalog
import splice.head.compact.HeadCompactSource

/** One head as /api/compact, /api/compaction/instructions and the capture switch see it. */
public data class TurnsHead(
    /** The topology's key for this head — the name every payload is keyed by. */
    val key: String,
    val compact: HeadCompactSource,
    /** The head's roster, which decides which model-scoped compaction rules apply to it. */
    val catalog: ModelCatalog? = null,
)

/** Every configured head, in topology order, read at CALL time. */
public fun interface TurnsHeads {
    public fun all(): List<TurnsHead>
}

/** The heads a by-name route resolves to: a KEY match first, then every wrapper-command match. */
public fun interface TurnsHeadLookup {
    public fun byName(name: String): List<TurnsHead>
}
