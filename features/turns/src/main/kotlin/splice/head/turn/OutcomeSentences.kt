// NEW: V4-404 — every outcome tag a turn can end on has its sentence, in words, with what to do. Before this
// only TurnConnEnd spoke: an upstream-failed turn's trace read `error:upstream-failed · 3 retries` and then
// Timing, because the sentence was written by the one surface that had a transport detail to quote and every
// other ending left the field empty (Marlin's walk of V4-349, bb54736ea). The sentence is recorded where the
// trace is closed (TurnTelemetry.closeTrace), so a surface that knows more still speaks first.
//
// PINNED BY NAME, NOT BY LIST: the tags are enumerated from their sources by OutcomeSentenceTest, which reads
// [OutcomeTag] and [ErrorType] as enums and every `OutcomeTags.error(…)` call out of this module's own
// sources, and fails BY NAME on a tag that has no sentence. The typed failures are a `when` over their enum
// with no else, so a new [ErrorType] does not even compile until it has one.
//
// A sentence is a clause and then what to do, after a semicolon, in the words of the one the conn-reset
// surface already writes. It never quotes a path or bytes: it is written here, not taken from a failure.
package splice.head.turn

import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.turn.CONN_RESET_OUTCOME
import splice.core.turn.ErrorType
import splice.core.usage.PlanLimit
import splice.core.util.LocalTimeText

/** The window's name comes from upstream text (any `seven_day…` claim), and the trace keeps [ERR_SNIPPET] characters:
 *  a name past this is cut, so the reset and the advice behind it always fit. */
private const val MAX_WINDOW_WORDS = 40

/** What a plan-limit sentence tells the reader to do: a re-send before the reset meets the same refusal. */
private const val WAIT_FOR_RESET = "retrying sooner cannot succeed, so wait for the reset, then retry"

internal object OutcomeSentences {

    /** The fixed tags. A null is a tag that is not a failure, and each says why beside it. */
    private val fixed: Map<OutcomeTag, String?> = mapOf(
        OutcomeTag.OK to null,
        // The client closed the connection: nobody is left to read a sentence, and nothing failed.
        OutcomeTag.CLIENT_ABORT to null,
        // A finished answer that happened to be empty, not a failure (StreamPromote ends it clean).
        OutcomeTag.EMPTY_MESSAGE to null,
        OutcomeTag.EMPTY_MODEL to "the model returned no content; retry the request",
        OutcomeTag.EMPTY_COMPACT to
            "the model returned no content for the compaction, so the conversation was not compacted; " +
            "retry the compaction",
        OutcomeTag.CANCELLED to
            "the turn was cancelled before it finished, for example when the provider stopped responding; " +
            "retry the request",
        OutcomeTag.UNEXPECTED to
            "splice hit an internal error on this turn; retry the request, and if it repeats read the daemon " +
            "log around this turn's time",
        OutcomeTag.RATE_LIMITED to
            "the provider kept rate limiting this account, so splice held the turn without contacting it; " +
            "wait for the limit to clear, then retry",
        // V4-419: the row's own words when its ending spoke none (a record older than the ending). A turn that
        // ends this way speaks the window and the reset instead: [planLimit].
        OutcomeTag.PLAN_LIMIT to
            "the provider says this plan's usage window is used up until its reset; $WAIT_FOR_RESET",
        OutcomeTag.ALL_ACCOUNTS_EXHAUSTED to
            "every account on this head is out of quota, so splice refused the turn without contacting the " +
            "provider; wait for the earliest quota reset or add an account, then retry",
        OutcomeTag.BUDGET_BLOCKED to
            "this head's daily spend budget is reached and its action is block, so splice refused the turn; " +
            "raise the budget or wait until the next UTC day, then retry",
        OutcomeTag.AUTH_MISSING to
            "this head has no credentials for its provider, so nothing was sent; sign in to the provider, " +
            "then retry",
        OutcomeTag.UPSTREAM_FAILED to
            "the provider failed the request and splice's retries did not get past it; wait a moment and " +
            "retry, and if it repeats read the provider's error in the message the client received",
        OutcomeTag.UPSTREAM_FRAME_TOO_LARGE to
            "the provider sent one streaming event larger than splice accepts; retry the request, which " +
            "usually comes back smaller",
        OutcomeTag.COMPACTION_PREFLIGHT_COMPACTABLE to
            "the estimated request exceeds this model's reserved context before any provider send; " +
            "compact the earlier conversation, then retry",
        OutcomeTag.COMPACTION_PREFLIGHT_FIRST_EXCHANGE to
            "the first exchange exceeds this model's window before any provider send; " +
            "shorten the request, then retry",
        OutcomeTag.COMPACTION_PREFLIGHT_COMPACT_OVERFLOW to
            "even the compaction cannot fit its estimated input and generated output; " +
            "shorten the conversation, then retry",
    )

    /** The kinds of `error:<kind>` that are not fixed [OutcomeTag] constants. */
    private val kinds: Map<String, String> = mapOf(
        CONN_RESET_OUTCOME to "the connection to the provider closed mid-request; retry",
        OutcomeTags.error("stopped") to
            "the operator stopped this turn, and it is not retried; send the request again if it is still wanted",
    )

    /** The sentence for [tag], or null when the tag is not a failure. */
    fun of(tag: String): String? {
        val known = OutcomeTag.entries.firstOrNull { it.wire == tag }
        val typed = ErrorType.entries.firstOrNull { OutcomeTags.failure(it) == tag }
        return when {
            known != null -> fixed[known]
            typed != null -> typed(typed)
            else -> kinds[tag]
        }
    }

    /** V4-419: what a turn ended by a spent plan window says: the window in words and the reset the provider
     *  named, in [times]'s zone (the machine's), then what to do. The turn that met the 429 and every turn held
     *  behind it speak through this one, so they name the same instant in the same words. */
    fun planLimit(limit: PlanLimit, times: LocalTimeText = LocalTimeText()): String =
        "this plan's ${limit.windowWords.take(MAX_WINDOW_WORDS)} window is used up until " +
            "${times.at(limit.resetEpochSeconds)}; $WAIT_FOR_RESET"

    private fun typed(type: ErrorType): String = when (type) {
        ErrorType.INVALID_REQUEST ->
            "the provider rejected the request as invalid, so resending it unchanged fails the same way; " +
                "change the request before retrying"
        ErrorType.AUTHENTICATION ->
            "the provider rejected this head's credentials; sign in to the provider again, then retry"
        ErrorType.PERMISSION ->
            "the provider says this account is not allowed to do that; use an account with access to it, " +
                "then retry"
        ErrorType.NOT_FOUND ->
            "the provider does not know the model or resource the request named; choose one the provider " +
                "offers, then retry"
        ErrorType.RATE_LIMIT ->
            "the provider is rate limiting this account; wait for the limit to clear or use another account, " +
                "then retry"
        ErrorType.API_ERROR -> "the provider failed on its side; retry in a moment"
        ErrorType.OVERLOADED -> "the provider is overloaded; retry in a moment"
    }
}
