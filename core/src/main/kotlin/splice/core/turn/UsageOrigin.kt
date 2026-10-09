package splice.core.turn

/** Where a [Usage] came from beyond its token buckets: whether it is a local code-mode step, the
 *  requests folded into it, and the output and context that ride beside the bill. */
public data class UsageOrigin(
    /** A client-facing code-mode step synthesized locally; retained through round usage folding. */
    val localStep: Boolean = false,
    /** A code-mode branch changed a result already accepted on this conversation key. */
    val codeModeDiverged: Boolean = false,
    /** Output already recorded by an independently owned raw round, not a client-turn stamp. */
    val recordedOutputTokens: Long = 0,
    /** The context the client is told about when this usage measured no input of its own: a code-mode
     *  step with no upstream round carries its conversation's last measured round here, because Claude
     *  Code reads every assistant message's usage as the context total. Only the client payload reads
     *  it, and only while the usage's inputTokens is zero; splice's own accounting keeps the raw buckets. */
    val clientContext: Usage? = null,
    /** Requests billed before the final one, missing bills, and whether this value owns a final request. */
    val history: UsageHistory = UsageHistory(request = if (localStep) UsageRequest.NONE else UsageRequest.POSTED),
)
