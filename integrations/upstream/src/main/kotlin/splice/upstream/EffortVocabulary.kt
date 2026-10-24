// NEW: vendor effort alias tables live on the provider; the responses dialect only calls this seam.
package splice.upstream

/** Maps a configured or aliased effort token onto the vendor's canonical rungs.
 *  A plain interface so a SAM lambda cannot silently drop [fromBudget]. */
public interface EffortVocabulary {
    public fun normalize(raw: String): String?

    public fun fromBudget(budget: Long): String?

    public fun floor(effort: String?): String? = effort

    public fun omitWhenDisabled(): Boolean = true
}
