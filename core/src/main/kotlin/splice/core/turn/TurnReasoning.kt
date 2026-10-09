package splice.core.turn

/** How a turn reasons and how that reasoning reaches the client. */
public data class TurnReasoning(
    val showReasoning: ReasoningDisplay,
    val effort: String,
    val summary: String?,
    val budgetTokens: Long?,
)
