// PORT-OF: model/UpstreamRoster.kt — one tier-suffix grammar shared by model listing and wire routing.
package splice.core.model

/** The trailing numeric tier hint a picker row may carry, shared by the catalog and its comparison. */
public object ModelTierSuffix {
    private val hint = Regex("\\[\\d+[km]]$", RegexOption.IGNORE_CASE)

    /** [id] with a trailing tier hint removed; every other id is returned unchanged. */
    public fun strip(id: String): String = id.replace(hint, "")

    /** True when [id] ends in a tier hint rather than a vendor-owned bracketed suffix. */
    public fun present(id: String): Boolean = hint.containsMatchIn(id)
}
