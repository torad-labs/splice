// NEW: the catalog refusals that depend on the head alone, not on what a runtime lists. Boot asks only these, since
// the models a runtime discovers are not known before the daemon runs; the roster check that needs them stays a
// per-head DEGRADED, never a refusal to boot.
package splice.core.topology

/** One static reason a head has no catalog: the key it concerns and what is wrong with it. */
public data class HeadCatalogRefusal(val field: String, val detail: String)

public object HeadCatalogStatics {
    /** The first static reason [head] can never have a catalog, or null. */
    public fun refusal(head: HeadConfig): HeadCatalogRefusal? = when {
        head.contextWindow?.let { it <= 0 } == true ->
            HeadCatalogRefusal("context_window", "head context_window must be positive")
        head.discoveryPrefix.isEmpty() ->
            HeadCatalogRefusal("discovery_prefix", "discovery prefix is the picker namespace and is never empty")
        else -> null
    }
}
