// NEW: failure-handling slice 2 (2026-10-26). The sentences for the leaves splice decides itself, and for why it
// refused a request before any provider saw it (final-design.md section 10, one example line per kind). How a line
// says a time, a size or a count is Spoken's; a provider's leaves are ProviderSentences', the path's PathSentences'.
//
// detekt holds a function under ten branches, so grouped leaves use small exhaustive whens: each words its own
// leaves and delegates the others. Every when names all leaves without an else, so a new leaf needs explicit coverage
// in every dispatch.
package splice.core.failure

/** The leaves splice decides itself. */
internal class SpliceSentences(private val spoken: Spoken) {
    private val declines = DeclineSentences(spoken)
    private val holds = HoldSentences(spoken)

    fun said(cause: FailureCause.Splice, facts: LineFacts): Said = when (cause) {
        is FailureCause.Splice.NoLogin -> noLogin(cause.loginCommand ?: facts.loginCommand, facts.head)
        is FailureCause.Splice.AtCapacity -> Said(
            "${facts.head} is already running ${spoken.count(cause.limit.toLong(), "turn")}, its limit",
            next = Next.Resend("Send the message again in a moment."),
        )
        is FailureCause.Splice.Declined -> declines.said(cause.why, facts)
        is FailureCause.Splice.TurnBudgetSpent -> budgetSpent(cause, facts.head)
        is FailureCause.Splice.HoldOutlasted -> holds.outlasted(cause, facts)
        is FailureCause.Splice.Stopped, is FailureCause.Splice.Protocol, is FailureCause.Splice.Internal,
        FailureCause.Splice.CompactionUnfinished,
        -> stopped(cause, facts)
    }

    /** A head that was never signed in names the command that signs it in, where the head has one. */
    private fun noLogin(command: String?, head: String): Said = Said(
        "$head has no login yet",
        next = Next.Then(
            command?.takeIf(String::isNotBlank)?.let { "Run $it, then send the message again." }
                ?: "Sign $head in, then send the message again.",
        ),
    )

    private fun budgetSpent(cause: FailureCause.Splice.TurnBudgetSpent, head: String): Said = Said(
        if (cause.beforeFirstAttempt) {
            "$head could not start this turn within ${spoken.span(cause.budgetMs)}"
        } else if (cause.atWall) {
            "$head made no progress before this session's time limit"
        } else {
            "$head made no progress for ${spoken.span(cause.budgetMs)}"
        },
        next = Next.Resend(SEND_AGAIN),
    )

    /** Turns splice stopped, or that stopped inside splice. */
    private fun stopped(cause: FailureCause.Splice, facts: LineFacts): Said = when (cause) {
        is FailureCause.Splice.Stopped -> stoppedBy(cause.by, facts.head)
        is FailureCause.Splice.Protocol -> protocol(cause.fault, facts.head)
        is FailureCause.Splice.Internal ->
            Said("This turn failed because of a fault in splice", next = Next.Resend(SEND_AGAIN))
        FailureCause.Splice.CompactionUnfinished -> Said(
            "The summary this request was waiting on did not finish on ${facts.head}",
            next = Next.Resend(SEND_AGAIN),
        )
        is FailureCause.Splice.NoLogin, is FailureCause.Splice.AtCapacity, is FailureCause.Splice.Declined,
        is FailureCause.Splice.TurnBudgetSpent, is FailureCause.Splice.HoldOutlasted,
        -> said(cause, facts)
    }

    private fun stoppedBy(by: StoppedBy, head: String): Said = when (by) {
        StoppedBy.RESTART -> Said("splice restarted during this turn", next = Next.Resend(SEND_AGAIN))
        StoppedBy.CONSOLE -> Said("This turn was stopped from the splice console")
        StoppedBy.HEAD_STOPPING -> Said("$head is shutting down", next = Next.Resend(SEND_AGAIN))
    }

    private fun protocol(fault: ProtocolFault, head: String): Said = when (fault) {
        ProtocolFault.CODE_MODE_START -> Said("$head's code-mode runtime did not start", next = Next.Resend(SEND_AGAIN))
        ProtocolFault.CODE_MODE_STATE -> Said("$head's code-mode run lost its state, so this turn stopped")
        ProtocolFault.DIALECT_UNSUPPORTED -> Said("$head sent something splice cannot translate, so this turn stopped")
    }
}

/** Why splice refused a request before any provider saw it. */
internal class DeclineSentences(private val spoken: Spoken) {
    fun said(why: Decline, facts: LineFacts): Said {
        val head = facts.head
        return when (why) {
            is Decline.TooLarge -> tooLarge(why)
            is Decline.Budget -> Said(
                "$head reached its daily budget of ${why.limit}, which resets ${spoken.at(why.resetsAt)}",
                next = Next.Then(SEND_AFTER),
            )
            is Decline.Preflight -> preflight(why, head)
            is Decline.UnknownModel -> Said("$head does not serve ${spoken.model(why.model)}")
            is Decline.Untranslatable -> Said(untranslatable(why.what, head))
            Decline.HeapBusy -> Said("splice is short of memory right now", next = Next.Resend(SEND_AGAIN))
            Decline.ReadTimeout -> Said("This request took too long to reach splice", next = Next.Resend(SEND_AGAIN))
            is Decline.BadBody, is Decline.Unfit, Decline.ForeignHost, Decline.BadLocalKey, is Decline.SpliceKeyAsLogin,
            Decline.LivenessProbe, Decline.NoSharedLogin, Decline.NotLaunched,
            -> local(why, facts)
        }
    }

    /** The head's door: a splice key that is not this head's, or a session that proved nothing on a head holding logins
     *  (PR #247 review, Oct 29), which sent no splice key at all and must not be told its key is bad. */
    private fun door(why: Decline, head: String): Said = if (why == Decline.NotLaunched) {
        Said(
            "$head serves the logins it holds only to sessions splice started, and this session was started " +
                "another way",
            next = Next.Then("Start this session again with the head's own command, such as `$head --resume`."),
        )
    } else {
        Said("This session's splice key is not valid", next = Next.Then("Start the session again from splice."))
    }

    /** Refusals about the request itself, its arrival and its caller. */
    private fun local(why: Decline, facts: LineFacts): Said = when (why) {
        is Decline.BadBody -> Said(arrived(why))
        is Decline.Unfit -> Said("This request needs more memory than splice can give one request")
        Decline.ForeignHost -> Said("splice answers only requests addressed to this machine")
        Decline.BadLocalKey, Decline.NotLaunched -> door(why, facts.head)
        is Decline.SpliceKeyAsLogin -> ownKey(why, facts)
        Decline.LivenessProbe -> Said("This request was a liveness probe, so no turn was started")
        Decline.NoSharedLogin -> Said(
            "${facts.head} on this machine has no free login for a turn shared from another machine, " +
                "so the turn was not sent",
        )
        is Decline.TooLarge, is Decline.Budget, is Decline.Preflight, is Decline.UnknownModel,
        is Decline.Untranslatable, Decline.HeapBusy, Decline.ReadTimeout,
        -> said(why, facts)
    }

    /** Incomplete only where fewer bytes arrived than the request declared: a body that arrived whole did not parse. */
    private fun arrived(why: Decline.BadBody): String {
        val declared = why.declaredBytes
        return if (declared != null && why.readBytes < declared) {
            "This request reached splice incomplete"
        } else {
            "splice could not parse this request"
        }
    }

    /** Splice's key in the login's place: the variables that put it there are what the person unsets. */
    private fun ownKey(why: Decline.SpliceKeyAsLogin, facts: LineFacts): Said = Said(
        "This session sent splice's own key where your ${facts.providerName ?: "own"} login belongs",
        next = Next.Then(
            if (why.variables.isEmpty()) {
                "Start the session again from a shell that does not set it."
            } else {
                "Unset ${spoken.list(why.variables)} in the shell that launched it, then start the session again."
            },
        ),
    )

    /** Both sizes where the request's own is known and over the limit; the limit alone otherwise. */
    private fun tooLarge(why: Decline.TooLarge): Said {
        val limit = spoken.bytes(why.limit)
        val size = why.bytes?.takeIf { it > why.limit }?.let(spoken::bytes)?.takeIf { it != limit }
        return Said(
            size?.let { "This request is $it, more than the $limit splice accepts" }
                ?: "This request is larger than the $limit splice accepts",
        )
    }

    /** Only a compactable prompt says the words that make Claude Code compact. */
    private fun preflight(why: Decline.Preflight, head: String): Said = Said(
        when (why.kind) {
            PreflightKind.COMPACTABLE -> "This prompt is too long for $head"
            PreflightKind.COMPACT_OVERFLOW -> "This conversation is too long for $head to summarize"
            PreflightKind.FIRST_EXCHANGE -> "This first message is too long for $head"
        },
        next = Next.Then("Its model holds ${spoken.tokens(why.windowTokens)} tokens."),
    )

    /** Output contracts and timeline semantics, with all other features delegated explicitly. */
    private fun untranslatable(what: RequestFeature, head: String): String = when (what) {
        RequestFeature.OUTPUT_FORMAT ->
            "$head cannot carry the output format this request asks for, so the turn was not sent"
        RequestFeature.STRUCTURED_OUTPUT ->
            "$head cannot carry the structured output this request asks for, so the turn was not sent"
        RequestFeature.DOCUMENT_CITATIONS ->
            "$head cannot translate the requested document citations, so the turn was not sent"
        RequestFeature.TOOL_CHOICE ->
            "$head cannot guarantee the tool choice this request asks for, so the turn was not sent"
        RequestFeature.DYNAMIC_TOOL_REMOVAL ->
            "$head cannot enforce a mid-conversation tool removal for this request, so the turn was not sent"
        RequestFeature.TOP_K, RequestFeature.STOP_SEQUENCES,
        RequestFeature.SERVICE_TIER, RequestFeature.SPEED,
        RequestFeature.CONTAINER, RequestFeature.DIAGNOSTICS,
        RequestFeature.INFERENCE_GEO, RequestFeature.USER_PROFILE_ID,
        RequestFeature.WORKSPACE_ID, RequestFeature.CACHE_CONTROL,
        RequestFeature.CACHE_ONLY_WARMUP, RequestFeature.TOOL_STRICTNESS,
        RequestFeature.TOOL_ALLOWED_CALLERS, RequestFeature.TOOL_TYPE,
        RequestFeature.TOOL_INPUT_EXAMPLES, RequestFeature.TOOL_EAGER_INPUT_STREAMING,
        RequestFeature.TOOL_CACHE_CONTROL, RequestFeature.REASONING_CONTEXT,
        -> untranslatableControls(what, head)
    }

    /** Sampling, reasoning, and request-policy controls retain their own existing sentences. */
    private fun untranslatableControls(what: RequestFeature, head: String): String = when (what) {
        RequestFeature.TOP_K ->
            "$head cannot carry top_k sampling for this request, so the turn was not sent"
        RequestFeature.STOP_SEQUENCES ->
            "$head cannot carry the stop sequences this request asks for, so the turn was not sent"
        RequestFeature.SERVICE_TIER ->
            "$head cannot carry the service tier this request asks for, so the turn was not sent"
        RequestFeature.SPEED ->
            "$head cannot carry the speed setting this request asks for, so the turn was not sent"
        RequestFeature.CACHE_CONTROL ->
            "$head cannot carry this request's cache-control breakpoint, so the turn was not sent"
        RequestFeature.CACHE_ONLY_WARMUP ->
            "$head cannot carry a cache-only warmup request, so the turn was not sent"
        RequestFeature.REASONING_CONTEXT ->
            "$head cannot carry this reasoning context, so the turn was not sent"
        RequestFeature.OUTPUT_FORMAT, RequestFeature.STRUCTURED_OUTPUT,
        RequestFeature.DOCUMENT_CITATIONS, RequestFeature.TOOL_CHOICE,
        RequestFeature.CONTAINER, RequestFeature.DIAGNOSTICS,
        RequestFeature.INFERENCE_GEO, RequestFeature.USER_PROFILE_ID,
        RequestFeature.WORKSPACE_ID, RequestFeature.TOOL_STRICTNESS,
        RequestFeature.TOOL_ALLOWED_CALLERS, RequestFeature.TOOL_TYPE,
        RequestFeature.TOOL_INPUT_EXAMPLES, RequestFeature.TOOL_EAGER_INPUT_STREAMING,
        RequestFeature.TOOL_CACHE_CONTROL, RequestFeature.DYNAMIC_TOOL_REMOVAL,
        -> untranslatableMetadata(what, head)
    }

    /** Provider-specific request metadata does not acquire a substitute or a silent default. */
    private fun untranslatableMetadata(what: RequestFeature, head: String): String = when (what) {
        RequestFeature.CONTAINER ->
            "$head cannot carry the container this request asks for, so the turn was not sent"
        RequestFeature.DIAGNOSTICS ->
            "$head cannot carry the diagnostics this request asks for, so the turn was not sent"
        RequestFeature.INFERENCE_GEO ->
            "$head cannot carry the inference region this request asks for, so the turn was not sent"
        RequestFeature.USER_PROFILE_ID ->
            "$head cannot carry the user profile this request asks for, so the turn was not sent"
        RequestFeature.WORKSPACE_ID ->
            "$head cannot carry the workspace this request asks for, so the turn was not sent"
        RequestFeature.OUTPUT_FORMAT, RequestFeature.STRUCTURED_OUTPUT,
        RequestFeature.DOCUMENT_CITATIONS, RequestFeature.TOOL_CHOICE,
        RequestFeature.TOP_K, RequestFeature.STOP_SEQUENCES,
        RequestFeature.SERVICE_TIER, RequestFeature.SPEED,
        RequestFeature.CACHE_CONTROL, RequestFeature.CACHE_ONLY_WARMUP,
        RequestFeature.TOOL_STRICTNESS, RequestFeature.TOOL_ALLOWED_CALLERS,
        RequestFeature.TOOL_TYPE, RequestFeature.TOOL_INPUT_EXAMPLES,
        RequestFeature.TOOL_EAGER_INPUT_STREAMING, RequestFeature.TOOL_CACHE_CONTROL,
        RequestFeature.REASONING_CONTEXT, RequestFeature.DYNAMIC_TOOL_REMOVAL,
        -> untranslatableTools(what, head)
    }

    /** Every tool feature is named explicitly, as are the leaves owned by the other groups. */
    private fun untranslatableTools(what: RequestFeature, head: String): String = when (what) {
        RequestFeature.TOOL_STRICTNESS ->
            "$head cannot guarantee strict tool calling for this request, so the turn was not sent"
        RequestFeature.TOOL_ALLOWED_CALLERS ->
            "$head cannot carry this tool's caller restriction, so the turn was not sent"
        RequestFeature.TOOL_TYPE ->
            "$head cannot carry this native tool type, so the turn was not sent"
        RequestFeature.TOOL_INPUT_EXAMPLES ->
            "$head cannot carry this tool's input examples, so the turn was not sent"
        RequestFeature.TOOL_EAGER_INPUT_STREAMING ->
            "$head cannot carry eager tool-input streaming for this request, so the turn was not sent"
        RequestFeature.TOOL_CACHE_CONTROL ->
            "$head cannot carry this tool's cache-control breakpoint, so the turn was not sent"
        RequestFeature.OUTPUT_FORMAT, RequestFeature.STRUCTURED_OUTPUT,
        RequestFeature.DOCUMENT_CITATIONS, RequestFeature.TOOL_CHOICE,
        RequestFeature.TOP_K, RequestFeature.STOP_SEQUENCES,
        RequestFeature.SERVICE_TIER, RequestFeature.SPEED,
        RequestFeature.CONTAINER, RequestFeature.DIAGNOSTICS,
        RequestFeature.INFERENCE_GEO, RequestFeature.USER_PROFILE_ID,
        RequestFeature.WORKSPACE_ID, RequestFeature.CACHE_CONTROL,
        RequestFeature.CACHE_ONLY_WARMUP, RequestFeature.REASONING_CONTEXT,
        RequestFeature.DYNAMIC_TOOL_REMOVAL,
        -> untranslatable(what, head)
    }
}
