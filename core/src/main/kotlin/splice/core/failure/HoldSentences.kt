// NEW: failure-handling slice 5 (2026-10-27). The sentences of a turn that holds for its cause to lift, and of a hold
// that ends without the turn going on (final-design.md section 10, the Limited, NeedsPayment and HoldOutlasted rows).
// A hold's line says what the turn met and when it continues; an outlasted hold's line says why it stopped in the
// held cause's own words, so a held limit, a held overload and a held spent account each read as themselves.
package splice.core.failure

import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The longest wait a line says as a stretch ("in 5 minutes"); a later one is said as a clock time. */
private val SAID_AS_A_STRETCH: Duration = Duration.ofHours(1)

/** What a hold line says next, by how it ends. */
private const val CONTINUES_AS_IT_LIFTS = "This turn waits and continues as soon as it lifts."

/** What a payment hold line says the person does, and that the turn goes on once they have. */
private const val CONTINUES_BY_ITSELF = " and this turn continues by itself."

/** The sentences of a held turn and of an outlasted hold. */
internal class HoldSentences(private val spoken: Spoken) {
    /** The line that opens a hold: what [cause] is, and when the turn goes on. */
    fun holding(cause: FailureCause, hold: Recovery.Hold, facts: LineFacts): Said =
        if (cause is FailureCause.Provider.Limited) {
            limit(cause, hold, facts)
        } else if (cause is FailureCause.Provider.NeedsPayment) {
            credit(cause, facts, CONTINUES_BY_ITSELF)
        } else {
            Said("${facts.head} is overloaded", next = Next.Then(until(hold, facts.now)))
        }

    /** A limit held: until its stated reset, softly where a probe may find it lifted sooner, or until it lifts. */
    private fun limit(cause: FailureCause.Provider.Limited, hold: Recovery.Hold, facts: LineFacts): Said {
        val head = facts.head
        val window = spoken.window(cause.window)
        val reached = if (window == null) "$head is at its usage limit" else "$head reached $window"
        val at = hold.until ?: return Said(
            "$head reached ${window ?: "its usage limit"} without saying when it resets",
            next = Next.Then(CONTINUES_AS_IT_LIFTS),
        )
        if (!hold.soft) return Said(reached, next = Next.Then(until(hold, facts.now)))
        val what = if (window == null) "$reached until ${spoken.at(at)}" else "$reached, which resets ${spoken.at(at)}"
        return Said(what, next = Next.Then("This turn waits and continues then, or sooner if the limit lifts."))
    }

    /** A spent account or an inactive subscription, which the person settles with the provider they pay. */
    private fun credit(cause: FailureCause.Provider.NeedsPayment, facts: LineFacts, then: String): Said {
        val whose = facts.providerName?.let { "${facts.head}'s $it" } ?: "${facts.head}'s"
        val with = facts.providerName?.let { " with $it" }.orEmpty()
        return if (cause.subscription) {
            Said("$whose subscription is not active", next = Next.Then("Renew it$with$then"))
        } else {
            Said("$whose account cannot pay for this turn", cause.said, Next.Then("Add credit$with$then"))
        }
    }

    /** When a held turn continues: in a stretch it can say, or at the clock time it waits for. */
    private fun until(hold: Recovery.Hold, now: Instant): String {
        val at = hold.until ?: return CONTINUES_AS_IT_LIFTS
        val left = Duration.between(now, at)
        return if (left < SAID_AS_A_STRETCH) {
            "This turn continues in ${spoken.span(left.toMillis().coerceAtLeast(0L))}."
        } else {
            "This turn waits until ${spoken.at(at)} and continues then."
        }
    }

    /** A hold that ended without the turn going on, in its held cause's words. */
    fun outlasted(cause: FailureCause.Splice.HoldOutlasted, facts: LineFacts): Said = when (cause.why) {
        Outlast.WALL -> handedOver(cause, facts)
        Outlast.TOO_FAR -> tooFar(cause, facts)
        Outlast.MISSED_RESETS -> missed(cause, facts)
        Outlast.MAX_HOLD -> week(cause, facts)
    }

    /** A limit the session cannot wait out, told to Claude Code with its reset (family L): one login names the reset
     *  it retries at, a pool the 30 s in which it checks every login again. */
    private fun handedOver(cause: FailureCause.Splice.HoldOutlasted, facts: LineFacts): Said {
        val head = facts.head
        val reset = cause.nextReset ?: return heldOn(cause.held, facts)
        val at = spoken.at(reset)
        return if (facts.request.otherLogin) {
            Said(
                "Every $head login is at its usage limit, the first until $at",
                next = Next.Then(
                    "Claude Code checks again in ${spoken.span(TimeUnit.SECONDS.toMillis(RECHECK_SECONDS))} " +
                        "and continues on the first login that frees.",
                ),
            )
        } else {
            val window = (cause.held as? FailureCause.Provider.Limited)?.window?.let(spoken::window)
            val what = if (window == null) {
                "$head is at its usage limit until $at"
            } else {
                "$head reached $window, " +
                    "which resets $at"
            }
            Said(what, next = Next.Then("Claude Code retries at that time."))
        }
    }

    /** A hold with no time stated, ended at the session's wall: the client sends the turn again and it holds anew. */
    private fun heldOn(held: FailureCause, facts: LineFacts): Said = if (held is FailureCause.Provider.NeedsPayment) {
        credit(held, facts, CONTINUES_BY_ITSELF).copy(next = Next.Resend(SEND_LATER))
    } else {
        Said("${facts.head} reached its usage limit without saying when it resets", next = Next.Resend(SEND_LATER))
    }

    /** A reset past the seven days a turn may hold. */
    private fun tooFar(cause: FailureCause.Splice.HoldOutlasted, facts: LineFacts): Said {
        val head = facts.head
        val at = cause.nextReset?.let(spoken::at)
        val held = cause.held
        return if (held is FailureCause.Provider.Limited) {
            val window = spoken.window(held.window) ?: "its usage limit"
            Said(
                "$head reached $window, which resets ${at ?: "later"}",
                next = Next.Then("That is longer than a turn can wait, so send the message again then."),
            )
        } else if (held is FailureCause.Provider.NeedsPayment) {
            credit(held, facts, ", then send the message again.")
        } else {
            Said("$head is overloaded until ${at ?: "later"}", next = Next.Resend(SEND_AFTER))
        }
    }

    /** A cause that did not lift at three times it stated. */
    private fun missed(cause: FailureCause.Splice.HoldOutlasted, facts: LineFacts): Said {
        val head = facts.head
        val next = cause.nextReset?.let { Next.Then("Its next stated reset is ${spoken.at(it)}.") } ?: Next.None
        val held = cause.held
        return if (held is FailureCause.Provider.Limited) {
            Said("$head's usage limit did not lift at the times it stated, so this turn stopped", next = next)
        } else if (held is FailureCause.Provider.NeedsPayment) {
            stillCannotPay(held, facts, "at the times it stated")
        } else {
            Said("$head stayed overloaded past the times it stated", next = Next.Resend(SEND_LATER))
        }
    }

    /** A hold that reached its seven days. */
    private fun week(cause: FailureCause.Splice.HoldOutlasted, facts: LineFacts): Said {
        val head = facts.head
        val held = cause.held
        return if (held is FailureCause.Provider.Limited) {
            Said("$head is still at its usage limit after 7 days, so this turn stopped")
        } else if (held is FailureCause.Provider.NeedsPayment) {
            stillCannotPay(held, facts, "after 7 days")
        } else {
            Said("$head stayed overloaded for 7 days", next = Next.Resend(SEND_LATER))
        }
    }

    private fun stillCannotPay(held: FailureCause.Provider.NeedsPayment, facts: LineFacts, how: String): Said {
        val whose = facts.providerName?.let { "${facts.head}'s $it" } ?: "${facts.head}'s"
        val with = facts.providerName?.let { " with $it" }.orEmpty()
        return if (held.subscription) {
            Said(
                "$whose subscription is still not active $how, so this turn stopped",
                next = Next.Then("Renew it$with, then send the message again."),
            )
        } else {
            Said(
                "$whose account still cannot pay $how, so this turn stopped",
                next = Next.Then("Add credit$with, then send the message again."),
            )
        }
    }
}
