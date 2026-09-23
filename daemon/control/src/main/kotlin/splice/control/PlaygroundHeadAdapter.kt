// NEW: LAYOUT-01 — the control-owned adapter from ManagedHead into the playground's projection, so the
// diagnostics feature reads the key and credential without importing ManagedHead.
package splice.control

import splice.control.api.HeadResolver
import splice.diagnostics.playground.PlaygroundHead
import splice.diagnostics.playground.PlaygroundHeadLookup

internal object PlaygroundHeadAdapter {
    /** The shared by-name lookup (key first, then every wrapper-command match). */
    fun lookup(resolver: HeadResolver): PlaygroundHeadLookup =
        PlaygroundHeadLookup { name -> resolver.headByName(name).map { PlaygroundHead(it.head.key, it.auth) } }
}
