// NEW: failure-handling slice 4 (2026-10-27). The line for an account its provider refuses: a model its plan leaves
// out, or the account itself until its owner acts (AccountBlock), which names the step and where its owner takes it.
package splice.core.failure

/** ProviderSentences is at detekt's function ceiling, so the forbidden line is worded here, for its verdicts. */
internal object AccountSentences {
    /** No re-send changes a block, and no sign-in does: the line names whose account, the step and where. The
     *  provider's own words are left out, since the step they name is said here in splice's. */
    fun forbidden(cause: FailureCause.Provider.Forbidden, facts: LineFacts): Said {
        val head = facts.head
        val block = cause.block ?: return Said("$head's account may not use this model", cause.said)
        val whose = block.login?.let { "$head's login $it" } ?: "$head's account"
        val terms = facts.providerName?.let { "$it's updated terms" } ?: "updated terms"
        return when (block.step) {
            OwnerStep.ACCEPT_TERMS -> Said(
                "$whose is blocked until its owner accepts $terms",
                next = Next.Then("Accept them in ${block.where} with that account, then send the message again."),
            )
        }
    }
}
