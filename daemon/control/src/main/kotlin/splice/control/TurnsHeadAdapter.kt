// NEW: LAYOUT-01 — control-owned adapters from ManagedHead into the turns feature's projection, so the
// compaction and capture routes read their head facts without importing ManagedHead.
package splice.control

import splice.control.api.HeadResolver
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.TurnsHeads

internal object TurnsHeadAdapter {
    fun heads(heads: Map<String, ManagedHead>): TurnsHeads = TurnsHeads { heads.values.map(::adapt) }

    /** The shared by-name lookup (key first, then every wrapper-command match). */
    fun lookup(resolver: HeadResolver): TurnsHeadLookup =
        TurnsHeadLookup { name -> resolver.headByName(name).map(::adapt) }

    private fun adapt(head: ManagedHead): TurnsHead =
        TurnsHead(key = head.head.key, compact = head.compact, catalog = head.catalog)
}
