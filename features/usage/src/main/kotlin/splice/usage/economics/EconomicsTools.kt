package splice.usage.economics

/** The hour's tool-definition sums. [deferralTurns] is the denominator for the tool averages and is 0 on a head
 *  whose dialect cannot defer, which the UI renders as "n/a". */
public data class EconomicsTools(
    val toolsEager: Long,
    val toolsDeferred: Long,
    val deferralTurns: Long,
)
