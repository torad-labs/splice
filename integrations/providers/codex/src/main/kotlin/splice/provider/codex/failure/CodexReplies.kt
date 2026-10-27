// NEW: failure-handling slice 3 (2026-10-26). The ChatGPT backend's deviations from the Responses standard, which
// claudex's Replies reads before the standard does (final-design.md section 12). Its plan-window body moves in from
// the login provider (CodexPlanLimitBody, read through planLimitFromBody until slice 3), and its two code readings
// move in from the shared classifier.
//
// Slice 4 (2026-10-27) moves in the 518n-2 reading: a completed round of a model the backend cuts is a ReasoningCut,
// read here with the model list, the tier bound and the words of the continuation, where they were four knobs and a
// policy in the Responses dialect. The continuation's builder stays in the dialect, which only replays.
//
// Not here: the code-mode runtime faults, which already build FailureCause.Splice.Protocol where they happen
// (CodeModeRuntimeFailures).
package splice.provider.codex.failure

import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.ProviderWords
import splice.core.failure.RefusedBy
import splice.core.turn.Usage
import splice.core.usage.FIVE_HOUR_CLAIM
import splice.core.usage.PlanLimit
import splice.core.usage.SEVEN_DAY_CLAIM
import splice.core.wire.HttpStatus
import splice.dialect.responses.failure.ResponsesReplies
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.OpenAiError
import splice.upstream.failure.OpenAiErrorEnvelope
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.ReplyFacts
import java.time.Instant

/**
 * Three answers the ChatGPT backend gives that the Responses standard reads differently.
 *
 * usage_limit_reached is a spent plan window, not a burst: its body names the reset and the window's own length
 * (V4-377, CodexPlanLimitBody). A 429 that names it is a limit until that instant, firmly, and the window it names
 * reaches a person's line.
 *
 * cyber_policy and bio_policy are the Trusted-Access gate's refusals (DR-72, live on claudex Aug 31 and Sep 1), where
 * the standard reads an invalid_request. Read as a refusal they are never re-sent: on Sep 1 the same 1.3 MB transcript
 * went up 242 times, 42 of them compactions that could not finish, because a refusal read as an api_error.
 *
 * websocket_connection_limit_reached is the backend's own capacity, so the round waits and comes back, rather than
 * ending as the rate limit its type spells.
 *
 * And one answer the standard reads as a finished round: gpt-5.5, gpt-5.6-luna and gpt-5.6-terra intermittently stop
 * their own reasoning at exactly 518n-2 tokens (516, 1034, 1552, ...) and finalize a shallower answer; gpt-5.6-sol does
 * not (probed 2026-07-19). Such a round is a ReasoningCut, which the engine continues up to its budget by replaying the
 * round's encrypted reasoning with a hidden commentary item asking the model to go on, as codex-rs, CodexCont and
 * codexcomp do. Past the sixth multiple a count on the fingerprint is a long reasoning that landed there, not a cut.
 * Changing the list takes a build, as the bound does (final-design.md section 8).
 */
public object CodexReplies : ProviderDeviation {
    private val planLimits = CodexPlanLimitBody()

    /** The models whose reasoning the backend cuts at 518n-2 tokens. */
    public val cutModels: Set<String> = setOf("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.5")

    /** The words of the hidden commentary item that asks a cut model to go on reasoning (codex-rs's own nudge). */
    public const val CONTINUE_THINKING: String = "Continue thinking..."

    /** A round [model] completed whose reasoning stopped at 518n-2 tokens, n at most [MAX_CUT_TIER], on a model the
     *  backend cuts; null for every other round, which the backend finished whole. */
    override fun completed(model: String, usage: Usage): FailureCause.Provider? {
        val reasoning = usage.reasoningTokens
        val onFingerprint = reasoning > 0 && (reasoning + CUT_BELOW) % CUT_PERIOD == 0L
        val cut = model in cutModels && onFingerprint && (reasoning + CUT_BELOW) / CUT_PERIOD <= MAX_CUT_TIER
        return if (cut) FailureCause.Provider.ReasoningCut(reasoning) else null
    }

    override fun http(reply: FailedReply): FailureCause.Provider? {
        val error = OpenAiErrorEnvelope.of(reply.body) ?: OpenAiError("", "", reply.body)
        return coded(error, ReplyFacts.retryAfterLift(reply) ?: Lift.Unstated) ?: usageLimit(reply)
    }

    override fun event(event: FailedEvent): FailureCause.Provider? =
        coded(ResponsesReplies.error(event.payload), Lift.Unstated)

    /** A 429's spent plan window as the body named it, which the hold still reads until slice 5 reads the cause. */
    override fun planLimit(reply: FailedReply): PlanLimit? =
        reply.takeIf { it.status == HttpStatus.TOO_MANY_REQUESTS }
            ?.let { planLimits.read(it.body, it.answeredAt.epochSecond) }

    /** The two readings a code decides, over HTTP and in band alike; [lift] is what the answer stated, if it did. */
    private fun coded(error: OpenAiError, lift: Lift): FailureCause.Provider? {
        val said = OpenAiReplies.said(error)
        return when {
            POLICY_CODES.any(error::names) -> FailureCause.Provider.Refused(RefusedBy.POLICY, said)
            error.names(WEBSOCKET_LIMIT) -> FailureCause.Provider.Overloaded(lift, loading = false, said)
            else -> null
        }
    }

    private fun usageLimit(reply: FailedReply): FailureCause.Provider? {
        val plan = planLimit(reply) ?: return null
        return FailureCause.Provider.Limited(
            Lift.At(Instant.ofEpochSecond(plan.resetEpochSeconds), firm = true, LiftSource.BODY),
            window(plan.claim),
            said(reply),
        )
    }

    /** The window the plan body named, as a line says it; a window of its own length is named by none of the three. */
    private fun window(claim: String): LimitWindow = when {
        claim == FIVE_HOUR_CLAIM -> LimitWindow.FIVE_HOUR
        claim == SEVEN_DAY_CLAIM -> LimitWindow.WEEKLY
        claim.startsWith("${SEVEN_DAY_CLAIM}_") -> LimitWindow.MODEL_WEEKLY
        else -> LimitWindow.OTHER
    }

    /** A usage_limit_reached body carries its sentence at the top level, not in an error object. */
    private fun said(reply: FailedReply): ProviderWords? = ErrorMessage.said(reply.body)
}

/** The codes the Trusted-Access gate refuses with; the standard reads OpenAI's own content-policy codes. */
private val POLICY_CODES = listOf("cyber_policy", "bio_policy")

/** The backend's own websocket capacity. */
private const val WEBSOCKET_LIMIT = "websocket_connection_limit_reached"

/** The period of the cut: the backend stops a cut model's reasoning just below a multiple of 518 tokens. */
private const val CUT_PERIOD = 518L

/** How far below each multiple of [CUT_PERIOD] it stops: reasoning_tokens == 518n - 2, observed 516, 1034, 1552. */
private const val CUT_BELOW = 2L

/** The highest n a count on the fingerprint is read as a cut at. */
private const val MAX_CUT_TIER = 6L
