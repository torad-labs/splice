// NEW: failure-handling slice 2 (2026-10-26). The sentences SpliceLines composes its lines from, one class per group of
// leaves, and the way a line says a time, a size, a count or an amount (final-design.md section 10, one example line
// per kind).
//
// detekt holds a function under ten branches and the Provider leaves number thirteen, so a group's leaves are worded by
// two whens that each name every leaf with no else: the first words its own and hands the rest to the second, whose
// last entry hands the first one's leaves back to it. Either is right for any leaf, and a new leaf compiles in neither
// until it has a sentence.
package splice.core.failure

import splice.core.util.LocalTimeText
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToLong

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val MS_PER_HOUR = 3_600_000L

/** Up to a minute and a half a stretch is said in seconds, up to an hour and a half in minutes, then in hours. */
private const val MOST_SECONDS = 90L
private const val MOST_MINUTES_S = 5_400L

private const val BYTES_PER_KB = 1_024L
private const val BYTES_PER_MB = 1_048_576.0

/** At ten megabytes and above a size is said whole; below, to a tenth. */
private const val WHOLE_MB = 10.0

/** The longest model name a line repeats; a longer one is called the model this request asked for. */
private const val MOST_MODEL_CHARS = 64

private const val SEND_AGAIN = "Send the message again."
private const val SEND_LATER = "Send the message again later."
private const val SEND_AFTER = "Send the message again after that time."

/** How a line says a time, a stretch of time, a size, a count, an amount, a limit window and a model. */
internal class Spoken(private val time: LocalTimeText) {
    /** A model name a person can read: no address, no key-shaped run, nothing past [MOST_MODEL_CHARS]. */
    private val shownModel = Regex("""[A-Za-z0-9][A-Za-z0-9._/:-]*""")
    private val hostShaped = Regex("""(?i)(?:[a-z0-9-]*[a-z][a-z0-9-]*\.)+[a-z]{2,}\b|://|\d{4}-\d{2}-\d{2}""")
    private val keyShaped = Regex("""[A-Za-z0-9]{24,}""")

    /** [instant] as a person reads a time: "Oct 26, 12:25 AM CDT", in the machine's zone. */
    fun at(instant: Instant): String = time.at(instant.epochSecond)

    /** [ms] as a person says a stretch of time: "31 seconds", "4 minutes", "2 hours". */
    fun span(ms: Long): String {
        val seconds = (ms + MS_PER_SECOND - 1) / MS_PER_SECOND
        return when {
            seconds <= MOST_SECONDS -> count(seconds, "second")
            seconds < MOST_MINUTES_S -> count((seconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE, "minute")
            else -> count((seconds + SECONDS_PER_HOUR / 2) / SECONDS_PER_HOUR, "hour")
        }
    }

    fun count(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /** What a turn that waits [waitMs] from [now] says it does: a wait of an hour or more is said as a clock time. */
    fun retry(waitMs: Long, now: Instant): String = when {
        waitMs < MS_PER_SECOND -> "This turn tries again now."
        waitMs < MS_PER_HOUR -> "This turn tries again in ${span(waitMs)}."
        else -> "This turn tries again at ${at(now.plusMillis(waitMs))}."
    }

    /** A token count with its thousands marked: "262,144". */
    fun tokens(n: Long): String = String.format(Locale.US, "%,d", n)

    /** A size in megabytes, or in kilobytes below one. */
    fun bytes(n: Long): String {
        val mb = n / BYTES_PER_MB
        return when {
            mb >= WHOLE_MB -> "${mb.roundToLong()} MB"
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb).replace(".0 MB", " MB")
            else -> "${(n + BYTES_PER_KB - 1) / BYTES_PER_KB} KB"
        }
    }

    /** A limit window as a person names it, or null where the provider named none a person knows. */
    fun window(window: LimitWindow?): String? = when (window) {
        LimitWindow.FIVE_HOUR -> "its 5-hour limit"
        LimitWindow.WEEKLY -> "its weekly limit"
        LimitWindow.MODEL_WEEKLY -> "its weekly limit for this model"
        LimitWindow.OTHER, null -> null
    }

    /** [name] as a line repeats it, or "the model this request asked for" when it is not a plain model name. */
    fun model(name: String): String {
        val plain = name.length <= MOST_MODEL_CHARS && shownModel.matches(name)
        val readable = plain && !hostShaped.containsMatchIn(name) && !keyShaped.containsMatchIn(name)
        return if (readable) name else "the model this request asked for"
    }

    /** [text] cut at a word to end, with its period, within [max] characters. */
    fun cut(text: String, max: Int): String {
        val room = text.lastIndexOf(' ', max - 1).takeIf { it > 0 } ?: max - 1
        return text.substring(0, room).trimEnd(',', ';', ':', ' ') + "."
    }
}

/** The thirteen leaves a provider reports. [ongoing]: the line opens a recovery, said as it happens. */
internal class ProviderSentences(private val spoken: Spoken) {
    fun said(cause: FailureCause.Provider, at: Position, facts: LineFacts, ongoing: Boolean): Said = when (cause) {
        is FailureCause.Provider.Limited -> limited(cause, facts.head, ongoing)
        is FailureCause.Provider.NeedsPayment -> payment(cause, facts)
        is FailureCause.Provider.Overloaded -> overloaded(cause.loading, facts.head, ongoing)
        is FailureCause.Provider.ServerError -> server(cause.fault, at, facts.head, ongoing)
        is FailureCause.Provider.TooLong -> tooLong(cause, facts)
        is FailureCause.Provider.AuthRejected -> login(cause.owner, facts, ongoing)
        is FailureCause.Provider.ToolTear -> tear(cause.runaway, facts.head)
        is FailureCause.Provider.Forbidden, is FailureCause.Provider.Rejected, is FailureCause.Provider.Refused,
        is FailureCause.Provider.Unrecognized, is FailureCause.Provider.ModelMissing, is FailureCause.Provider.Empty,
        -> verdict(cause, at, facts, ongoing)
    }

    /** The provider's verdicts on the request, and its empty answers. */
    private fun verdict(cause: FailureCause.Provider, at: Position, facts: LineFacts, ongoing: Boolean): Said {
        val head = facts.head
        return when (cause) {
            is FailureCause.Provider.Forbidden -> Said("$head's account may not use this model", cause.said)
            is FailureCause.Provider.Rejected -> Said(
                if (cause.staleReasoning) {
                    "$head could not use the earlier reasoning in this conversation"
                } else {
                    "$head rejected the request as sent"
                },
                cause.said,
            )
            is FailureCause.Provider.Refused -> Said(refused(cause.by, head), cause.said)
            is FailureCause.Provider.Unrecognized -> unrecognized(cause, head)
            is FailureCause.Provider.ModelMissing -> missing(cause.model, facts)
            is FailureCause.Provider.Empty -> empty(cause.reasoningOnly, head)
            is FailureCause.Provider.Limited, is FailureCause.Provider.NeedsPayment,
            is FailureCause.Provider.Overloaded, is FailureCause.Provider.ServerError, is FailureCause.Provider.TooLong,
            is FailureCause.Provider.AuthRejected, is FailureCause.Provider.ToolTear,
            -> said(cause, at, facts, ongoing)
        }
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

/** The three ways the path to a provider breaks. */
internal object PathSentences {
    fun said(cause: FailureCause.Path, facts: LineFacts, ongoing: Boolean): Said {
        val head = facts.head
        val what = when (cause) {
            is FailureCause.Path.Unreachable -> unreachable(cause, head, ongoing)
            is FailureCause.Path.Torn -> torn(cause, head, ongoing)
            is FailureCause.Path.Stalled -> when (cause.tier) {
                Stall.FIRST_BYTE -> "$head did not start answering in time"
                Stall.IDLE -> "$head went silent mid-answer" + if (ongoing) "" else " and did not recover"
            }
        }
        return Said(what, next = Next.Resend(SEND_AGAIN))
    }

    private fun unreachable(cause: FailureCause.Path.Unreachable, head: String, ongoing: Boolean): String {
        val side = if (cause.local) "$head's model server" else "$head's provider"
        return when (cause.why) {
            Unreach.REFUSED -> refused(side, cause.local, ongoing)
            Unreach.DNS -> "$head could not reach its provider because the address did not resolve"
            Unreach.TLS -> "$head could not open a secure connection to its provider"
            Unreach.RESET_BEFORE_REPLY -> "$side closed the connection before answering"
            Unreach.WRITE_STALLED -> "$head stopped reading the request while splice was sending it"
        }
    }

    /** A model server on this machine that refuses is down; a remote provider that refuses only refused. */
    private fun refused(side: String, local: Boolean, ongoing: Boolean): String = when {
        !local -> "$side refused the connection"
        ongoing -> "$side is not answering"
        else -> "$side is not running"
    }

    private fun torn(cause: FailureCause.Path.Torn, head: String, ongoing: Boolean): String = when (cause.why) {
        Tear.RESET, Tear.SOCKET_CLOSED, Tear.NO_TERMINAL -> {
            val cut = if (ongoing) "was cut off" else "kept getting cut off"
            "$head's answer $cut" + if (cause.toolOpen) " inside a tool call" else ""
        }
        Tear.NO_EVENTS -> "$head closed the connection before sending anything"
        Tear.OVERSIZED_EVENT -> "$head sent a stream event larger than splice accepts"
    }
}

/** The leaves splice decides itself. */
internal class SpliceSentences(private val spoken: Spoken) {
    private val declines = DeclineSentences(spoken)

    fun said(cause: FailureCause.Splice, facts: LineFacts): Said = when (cause) {
        is FailureCause.Splice.NoLogin -> noLogin(cause.loginCommand ?: facts.loginCommand, facts.head)
        is FailureCause.Splice.AtCapacity -> Said(
            "${facts.head} is already running ${spoken.count(cause.limit.toLong(), "turn")}, its limit",
            next = Next.Resend("Send the message again in a moment."),
        )
        is FailureCause.Splice.Declined -> declines.said(cause.why, facts)
        is FailureCause.Splice.TurnBudgetSpent -> budgetSpent(cause, facts.head)
        is FailureCause.Splice.HoldOutlasted -> outlasted(cause, facts.head)
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

    /** A hold that outlasted its cause. Slice 5 words each held cause's own outlasting. */
    private fun outlasted(cause: FailureCause.Splice.HoldOutlasted, head: String): Said = Said(
        when (cause.why) {
            Outlast.MISSED_RESETS -> "$head did not recover at the times it stated"
            Outlast.MAX_HOLD -> "$head did not recover while this turn waited"
        },
        next = Next.Resend(cause.nextReset?.let { "Its next stated reset is ${spoken.at(it)}." } ?: SEND_LATER),
    )
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
            is Decline.BadBody, Decline.ForeignHost, Decline.BadLocalKey, Decline.LivenessProbe, Decline.NoSharedLogin,
            -> local(why, facts)
        }
    }

    /** Refusals about the request's own arrival and its caller. */
    private fun local(why: Decline, facts: LineFacts): Said = when (why) {
        is Decline.BadBody -> Said("This request reached splice incomplete")
        Decline.ForeignHost -> Said("splice answers only requests addressed to this machine")
        Decline.BadLocalKey ->
            Said("This session's splice key is not valid", next = Next.Then("Start the session again from splice."))
        Decline.LivenessProbe -> Said("This request was a liveness probe, so no turn was started")
        Decline.NoSharedLogin -> Said(
            "${facts.head} on this machine has no free login for a turn shared from another machine, " +
                "so the turn was not sent",
        )
        is Decline.TooLarge, is Decline.Budget, is Decline.Preflight, is Decline.UnknownModel,
        is Decline.Untranslatable, Decline.HeapBusy, Decline.ReadTimeout,
        -> said(why, facts)
    }

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

    private fun untranslatable(what: RequestFeature, head: String): String = when (what) {
        RequestFeature.OUTPUT_FORMAT ->
            "$head cannot carry the output format this request asks for, so the turn was not sent"
        RequestFeature.STRUCTURED_OUTPUT ->
            "$head cannot carry the structured output this request asks for, so the turn was not sent"
        RequestFeature.TOOL_CHOICE ->
            "$head cannot guarantee the tool choice this request asks for, so the turn was not sent"
    }
}
