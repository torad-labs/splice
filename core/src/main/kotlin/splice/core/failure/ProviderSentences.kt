// NEW: failure-handling slice 2 (2026-10-26). The sentences for the leaves a provider reports
// (final-design.md section 10, one example line per kind).
//
// detekt holds a function under ten branches and the Provider leaves number fourteen, so the leaves are worded by two
// whens that each name every leaf with no else: the first words its own and hands the rest to the second, whose last
// entry hands the first one's leaves back to it. Either is right for any leaf, and a new leaf compiles in neither
// until it has a sentence.
package splice.core.failure

/** The fourteen leaves a provider reports. [ongoing]: the line opens a recovery, said as it happens. */
internal class ProviderSentences(private val spoken: Spoken) {
    fun said(cause: FailureCause.Provider, at: Position, facts: LineFacts, ongoing: Boolean): Said = when (cause) {
        is FailureCause.Provider.Limited -> limited(cause, facts.head, ongoing)
        is FailureCause.Provider.NeedsPayment -> payment(cause, facts)
        is FailureCause.Provider.Overloaded -> overloaded(cause.loading, facts.head, ongoing)
        is FailureCause.Provider.ServerError -> server(cause.fault, at, facts.head, ongoing)
        is FailureCause.Provider.TooLong -> tooLong(cause, facts)
        is FailureCause.Provider.AuthRejected -> login(cause.owner, facts, ongoing)
        is FailureCause.Provider.ToolTear -> tear(cause.runaway, facts.head)
        is FailureCause.Provider.ReasoningCut, is FailureCause.Provider.Forbidden, is FailureCause.Provider.Rejected,
        is FailureCause.Provider.Refused,
        is FailureCause.Provider.Unrecognized, is FailureCause.Provider.ModelMissing, is FailureCause.Provider.Empty,
        -> verdict(cause, at, facts, ongoing)
    }

    /** The provider's verdicts on the request, and its empty answers. */
    private fun verdict(cause: FailureCause.Provider, at: Position, facts: LineFacts, ongoing: Boolean): Said {
        val head = facts.head
        return when (cause) {
            is FailureCause.Provider.Forbidden -> AccountSentences.forbidden(cause, facts)
            is FailureCause.Provider.Rejected -> Said(rejected(cause.staleReasoning, head), cause.said)
            is FailureCause.Provider.Refused -> Said(refused(cause.by, head), cause.said)
            is FailureCause.Provider.Unrecognized -> unrecognized(cause, head)
            is FailureCause.Provider.ModelMissing -> missing(cause.model, facts)
            is FailureCause.Provider.Empty -> empty(cause.reasoningOnly, head)
            // A cut round is continued without a line (section 8); were one ever said, it reads as a cut answer.
            is FailureCause.Provider.ReasoningCut ->
                Said("$head's reasoning was cut short", next = Next.Resend(SEND_AGAIN))
            is FailureCause.Provider.Limited, is FailureCause.Provider.NeedsPayment,
            is FailureCause.Provider.Overloaded, is FailureCause.Provider.ServerError, is FailureCause.Provider.TooLong,
            is FailureCause.Provider.AuthRejected, is FailureCause.Provider.ToolTear,
            -> said(cause, at, facts, ongoing)
        }
    }

    /** What the provider rejected: the reasoning this conversation carried, or the request as it was sent. */
    private fun rejected(staleReasoning: Boolean, head: String): String = if (staleReasoning) {
        "$head could not use the earlier reasoning in this conversation"
    } else {
        "$head rejected the request as sent"
    }

    /** A limit names its window where the provider named one, and its reset where it stated one. */
    private fun limited(cause: FailureCause.Provider.Limited, head: String, ongoing: Boolean): Said {
        val window = spoken.window(cause.window)
        val reached = if (window == null) "$head is at its usage limit" else "$head reached $window"
        if (ongoing) return Said(if (window == null) "$head is limiting requests for a moment" else reached)
        return when (val lift = cause.lift) {
            is Lift.At -> {
                val reset = spoken.at(lift.at)
                val what = if (window == null) "$reached until $reset" else "$reached, which resets $reset"
                Said(what, next = Next.Resend(SEND_AFTER))
            }
            Lift.Unstated -> Said("$reached and did not say when it resets", next = Next.Resend(SEND_LATER))
        }
    }

    /** A spent account or an inactive subscription, named with the provider a person pays where it is known. */
    private fun payment(cause: FailureCause.Provider.NeedsPayment, facts: LineFacts): Said {
        val whose = facts.providerName?.let { "${facts.head}'s $it" } ?: "${facts.head}'s"
        val with = facts.providerName?.let { " with $it" }.orEmpty()
        return if (cause.subscription) {
            Said("$whose subscription is not active", next = Next.Resend("Renew it$with, then send the message again."))
        } else {
            Said(
                "$whose account cannot pay for this turn",
                cause.said,
                Next.Resend("Add credit$with, then send the message again."),
            )
        }
    }

    private fun overloaded(loading: Boolean, head: String, ongoing: Boolean): Said = Said(
        when {
            loading -> "$head is still loading its model"
            ongoing -> "$head is overloaded"
            else -> "$head stayed overloaded"
        },
        next = Next.Resend("Send the message again in a few minutes."),
    )

    private fun server(fault: ServerFault, at: Position, head: String, ongoing: Boolean): Said {
        val mid = if (at.moment == Moment.MID_CONTENT) " mid-answer" else ""
        val what = when (fault) {
            ServerFault.FOREIGN_KEY -> "$head's provider rejected a key this login never sent, a fault on its side"
            ServerFault.GATEWAY_TIMEOUT -> "$head timed out" + mid.ifEmpty { " before answering" }
            ServerFault.INTERNAL, ServerFault.BAD_GATEWAY ->
                if (ongoing) "$head hit a server error$mid" else "$head kept failing$mid with a server error"
        }
        return Said(what, next = Next.Resend(SEND_AGAIN))
    }

    /** Only a prompt that is not a compaction or a first exchange says the words that make Claude Code compact. */
    private fun tooLong(cause: FailureCause.Provider.TooLong, facts: LineFacts): Said {
        val head = facts.head
        val what = when {
            facts.request.compaction -> "This conversation is too long for $head to summarize"
            facts.request.firstExchange -> "This first message is too long for $head"
            cause.whileAnswering ->
                "The answer filled $head's context window before it finished, so the prompt is too long to continue"
            else -> "This prompt is too long for $head"
        }
        val holds = cause.limitTokens?.let { Next.Then("Its model holds ${spoken.tokens(it)} tokens.") }
        return Said(what, next = holds ?: Next.None)
    }

    private fun login(owner: LoginOwner, facts: LineFacts, ongoing: Boolean): Said = when (owner) {
        LoginOwner.CLIENT -> Said(
            "${facts.providerName ?: facts.head} did not accept this session's login",
            next = Next.Then("Run /login, then send the message again."),
        )
        LoginOwner.SPLICE -> Said(
            if (ongoing) {
                "${facts.head}'s login expired, so splice is signing it in again"
            } else {
                "${facts.head}'s login expired and could not be renewed"
            },
            next = Next.Then(
                facts.loginCommand?.let { "Run $it, then send the message again." }
                    ?: "Sign ${facts.head} in again, then send the message again.",
            ),
        )
    }

    private fun tear(runaway: Boolean, head: String): Said = Said(
        if (runaway) {
            "$head's answer grew larger than splice can hold"
        } else {
            "$head's answer broke off inside a tool call"
        },
        next = Next.Resend(SEND_AGAIN),
    )

    private fun refused(by: RefusedBy, head: String): String = when (by) {
        RefusedBy.MODEL -> "The model on $head declined to answer"
        RefusedBy.POLICY -> "$head declined this request under its usage policy"
        RefusedBy.FILTER -> "$head's content filter stopped this answer"
    }

    private fun unrecognized(cause: FailureCause.Provider.Unrecognized, head: String): Said =
        if (cause.clientError) {
            Said("$head refused this turn in a way splice does not recognize", cause.said)
        } else {
            Said("$head failed in a way splice does not recognize", cause.said, Next.Resend(SEND_AGAIN))
        }

    /** A model server on this machine can load the model; a remote provider's catalog is not the person's to change. */
    private fun missing(model: String, facts: LineFacts): Said = Said(
        "${facts.head} does not serve ${spoken.model(model)}",
        next = if (facts.local) Next.Then("Load it in the model server, then send the message again.") else Next.None,
    )

    private fun empty(reasoningOnly: Boolean, head: String): Said = Said(
        if (reasoningOnly) "$head finished thinking without an answer" else "$head finished without an answer",
        next = Next.Resend(SEND_AGAIN),
    )
}
