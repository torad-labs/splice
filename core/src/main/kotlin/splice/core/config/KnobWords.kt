// NEW: Oct 13, 2026. The one list of words each word-valued knob takes. A word outside its list is
// refused by name at every layer (ConfigCoercion), never mapped to a fallback, and GET /api/config sends
// the list so the console builds the control from it (gap 458, splice-lead). Before this, quotaPoll read
// any word but "off" as auto, and showReasoning read any unknown word as thinking, which is not even its
// default. The budget words lived as a private copy in ConfigCoercion and an internal one in
// :features-usage, which now reads these. effort and summary are splice's own words too (splice-lead, Oct 13):
// how each becomes a vendor's rung stays in the provider modules (DefaultEffortVocabulary, GrokEffortVocabulary,
// ResponsesEffort), which already map every word listed here.
package splice.core.config

/** The words a word-valued knob takes, in the order a person picks from. */
public object KnobWords {
    public const val BUDGET_WARN: String = "warn"
    public const val BUDGET_BLOCK: String = "block"

    /** The actions a budget may name: [BUDGET_WARN] or [BUDGET_BLOCK]. */
    public val BUDGET_ACTIONS: List<String> = listOf(BUDGET_WARN, BUDGET_BLOCK)

    /** Whether splice reads each head's plan limits. */
    public val QUOTA_POLL: List<String> = listOf("auto", "off")

    /** Where a model's reasoning shows: in the reply, in Claude Code's thinking block, or nowhere. */
    public val SHOW_REASONING: List<String> = listOf("text", "thinking", "off")

    /** How hard a model reasons by default, from none to max. Unset is the model's own. */
    public val EFFORT: List<String> = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /** How much of a model's reasoning it summarises back. */
    public val SUMMARY: List<String> = listOf("detailed", "concise", "auto", "none")

    /** The list [knob] takes, or null when it takes any text. */
    public fun of(knob: Knob): List<String>? = when (knob) {
        Knob.QUOTA_POLL -> QUOTA_POLL
        Knob.SHOW_REASONING -> SHOW_REASONING
        Knob.BUDGET_DEFAULT_ACTION -> BUDGET_ACTIONS
        Knob.EFFORT -> EFFORT
        Knob.SUMMARY -> SUMMARY
        else -> null
    }
}
