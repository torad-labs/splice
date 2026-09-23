// NEW: LAYOUT-01 — the head facts one playground run needs: the key the probe names in the topology it
// re-reads, and the head-owned credential it sends with. The control plane adapts its wider ManagedHead
// into this projection, so the diagnostics feature never depends upward on the control plane.
package splice.diagnostics.playground

import splice.core.auth.AuthProvider

/** One head as the playground sees it. */
public data class PlaygroundHead(val key: String, val auth: AuthProvider)

/** The heads a playground body's `head` resolves to: a KEY match first, then every wrapper-command
 *  match. The route runs against the first. */
public fun interface PlaygroundHeadLookup {
    public fun byName(name: String): List<PlaygroundHead>
}
