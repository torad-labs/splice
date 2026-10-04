// NEW: LAYOUT-01 — the control-owned adapter from ManagedHead into the models page's projection, so the
// models feature reads each head's catalog without importing ManagedHead.
package splice.app.control

import splice.models.roster.RosterHead

internal object RosterHeadAdapter {
    /** In the heads map's order, keyed by the map key the declared roster is keyed by. The catalog is a
     *  reference the route reads through `live()`, so building the list once loses nothing. */
    fun heads(heads: Map<String, ManagedHead>): List<RosterHead> =
        heads.map { (key, head) -> RosterHead(key, head.catalog) }
}
