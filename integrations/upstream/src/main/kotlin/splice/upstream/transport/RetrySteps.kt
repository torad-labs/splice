// NEW: failure-handling slice 4, in place of TransportSteps.kt. RetryEngine's steps, run for one post: every failure a
// send meets (an answer the head's reader read, a transport error, a stream torn before the client saw a frame) is
// asked of the engine with where the turn stood and the turn's one ledger, and the step it answers is taken here: a
// wait through the turn's waits, one token exchange, a move to another login, or the post's ending. Nothing here
// counts or plans a retry of its own: what a step spends is recorded in the ledger, which the engine reads.
package splice.upstream.transport

import splice.core.auth.RefreshOutcome
import splice.core.failure.FailureCause
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Tear
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerfTiming
import splice.core.util.ERR_SNIPPET
import splice.core.util.WallClock
import splice.upstream.retry.Exchange
import splice.upstream.retry.How
import splice.upstream.retry.MAX_AMENDS
import splice.upstream.retry.MAX_ATTEMPTS_OUTSIDE_HOLDS
import splice.upstream.retry.RetryEngine
import splice.upstream.retry.RetryStep
import splice.upstream.retry.Spend
import splice.upstream.retry.TurnFacts
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

internal class RetrySteps(
    private val budget: RetryBudget,
    private val wall: WallClock,
    private val transportFailures: TransportFailures,
) {
    private val endings = RetryEndings()

    /** An answer that failed, as the head's reader read it. The dialect's own repair of a request it built comes
     *  first, once per turn (the engine's amend budget): stale reasoning left out, a tool surface the provider refused
     *  dropped. Otherwise the engine says what follows, from where the turn stood: before the client saw a frame, or
     *  after it, when no other login may take the turn. */
    suspend fun failed(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        bodyJson: String,
        state: RetryState,
    ): LoopStep<Nothing> {
        state.lastErr = failed
        state.lastThrown = null
        state.lastTear = null
        amended(ctx, failed, bodyJson, state)?.let { return it }
        val cause = askedAs(ctx, failed)
        val moment = if (ctx.recovery.clientFrameEmitted()) Moment.MID_CONTENT else Moment.BEFORE_COMMIT
        val at = Position(moment, streamBegan = false)
        val step = next(cause, at, ctx, state)
        noteFailed(ctx, failed, step, state)
        return take(step, Asked(cause, at, failed), ctx, state)
    }

    /** A transport error. Once the client saw a frame it is the head's round runner's to recover, and it goes up as
     *  the error it is; before that, a path that broke before any answer, or a stream torn after its handoff, is
     *  asked of the engine, and its ending is the error itself, which the head reads (TransportFaults). */
    suspend fun thrown(e: Throwable, ctx: PostContext, streamHandedOff: Boolean, state: RetryState): LoopStep<Nothing> {
        // A stream the client saw a frame of is the round runner's; one that failed for a reason that is no tear is
        // the failure it is; and a cancellation is nobody's to plan.
        val recoverable = !ctx.recovery.clientFrameEmitted() && transportFailures.isRetryableTransport(e)
        val mine = !streamHandedOff || recoverable
        if (e is CancellationException || !mine) throw e
        val cause = if (streamHandedOff) TORN_BEFORE_CLIENT else TransportFaults.of(e, local = false)
        val asked = Asked(cause, Position(Moment.BEFORE_COMMIT, streamBegan = false))
        val step = next(cause, asked.at, ctx, state)
        if (step !is RetryStep.Again || step.how != How.SAME) throw e
        // A stream torn after its handoff is sent fresh, never as a send that may have reached the provider twice.
        val phase = transportFailures.phaseOf(e).takeUnless { streamHandedOff }
        noteThrown(e, ctx, state, phase)
        state.lastThrown = e
        state.lastTear = null
        val kind = if (phase == TransportFailurePhase.POST_SEND) RetryKind.POST_SEND else RetryKind.ORDINARY
        return sendAgain(step, asked, ctx, state, kind)
    }

    /** A stream the handler read torn before the client saw a frame: sent fresh while the engine allows it, and
     *  otherwise ended as the tear it is. */
    suspend fun torn(e: IOException, ctx: PostContext, state: RetryState): LoopStep<Nothing> {
        val ended = LoopStep.Done(UpstreamPost.Ended(StreamTornBeforeClient(e)))
        if (ctx.recovery.clientFrameEmitted() || !transportFailures.isRetryableTransport(e)) return ended
        val asked = Asked(TORN_BEFORE_CLIENT, Position(Moment.BEFORE_COMMIT, streamBegan = false))
        val step = next(asked.cause, asked.at, ctx, state)
        if (step !is RetryStep.Again || step.how != How.SAME) return ended
        ctx.onRetry(
            "stream torn before first client frame, reissue ${state.ledger.attempts}/$MAX_ATTEMPTS_OUTSIDE_HOLDS: " +
                "${e::class.simpleName} ${TransportFaultLine.of(e, ctx.url).take(ERR_SNIPPET)}",
        )
        state.lastTear = StreamTornBeforeClient(e)
        state.lastThrown = null
        return sendAgain(step, asked, ctx, state, RetryKind.ORDINARY)
    }

    /** The post's own give-up, before an attempt: its deadline or the turn's wait was spent. It ends with the last
     *  failure it met: a transport error is thrown as it was, a torn stream ends as the tear, and an answer as read. */
    fun gaveUp(ctx: PostContext, state: RetryState): LoopStep<Nothing> {
        state.lastThrown?.let { throw it }
        state.lastTear?.let { return LoopStep.Done(UpstreamPost.Ended(it)) }
        return endings.done(state.lastErr, null, state, ctx)
    }

    private fun next(cause: FailureCause, at: Position, ctx: PostContext, state: RetryState): RetryStep {
        val facts = TurnFacts(
            pooled = ctx.pooled,
            // The account handoff knows which login is free; a pooled post hands the turn to it, and the handoff
            // ends the turn where none is.
            freeLogin = ctx.pooled,
            warmWaitLeftMs = state.warmWaitLeftMs(ctx),
            // The head's round runner continues a cut answer; a post never does.
            continuesReasoning = false,
            canContinue = false,
            wallLeftMs = budget.leftMs(ctx, state.t0),
            earliestLift = null,
        )
        return RetryEngine.next(cause, at, state.ledger, facts, Instant.ofEpochMilli(wall()))
    }

    /** What the engine is asked about [failed]: a credential this provider named dead behind another status, which a
     *  renewal can change, is asked as splice's own login refused (ProviderDeviation.deadCredential); any other answer
     *  as its reader read it. */
    private fun askedAs(ctx: PostContext, failed: RetryOutcome.Failed): FailureCause {
        val renewable = failed.deadCredential && ctx.auth.allowRefreshAfterFailure(deadCredential = true)
        return if (renewable) FailureCause.Provider.AuthRejected(LoginOwner.SPLICE, failed.cause.said) else failed.cause
    }

    private suspend fun take(step: RetryStep, asked: Asked, ctx: PostContext, state: RetryState): LoopStep<Nothing> =
        when (step) {
            is RetryStep.Again -> when (step.how) {
                How.SAME -> sendAgain(step, asked, ctx, state, RetryKind.ORDINARY)
                How.REFRESH_LOGIN -> renew(step, asked, ctx, state)
                // Stale reasoning the dialect could not leave out, and the continuations only a round runner makes.
                How.AMEND_REASONING, How.REANCHOR, How.CONTINUE_REASONING, How.FOLD_CONTINUE ->
                    endings.done(asked.failed, null, state, ctx)
            }
            RetryStep.SwitchLogin -> LoopStep.Done(UpstreamPost.Refused(endings.ended(asked.failed, null, state, ctx)))
            is RetryStep.End -> endings.done(asked.failed, step.cause, state, ctx)
            // Until slice 5 holds a turn, a hold ends it at once with today's retryable wire and the interim line.
            is RetryStep.Hold, RetryStep.Keep -> endings.done(asked.failed, null, state, ctx)
        }

    /** The step's wait, through the turn's waits, then the same request again. A wait a limit or a spent account
     *  asks for is spent from the warm session's own allowance, so the engine reads what is left of it. */
    private suspend fun sendAgain(
        step: RetryStep.Again,
        asked: Asked,
        ctx: PostContext,
        state: RetryState,
        kind: RetryKind,
    ): LoopStep<Nothing> {
        val cause = asked.cause
        val warm = cause is FailureCause.Provider.Limited || cause is FailureCause.Provider.NeedsPayment
        if (warm) ctx.limits.warmWait?.spend(step.afterMs)
        state.ledger.took(step, cause)
        if (step.afterMs > 0L) ctx.timedBackoff { state.retry.waits.wait(step.afterMs, cause, asked.at) }
        state.pendingRetry = kind
        return LoopStep.Continue
    }

    /** One token exchange per renewal step: renewed, the request goes again at once, on no attempt of its own; not
     *  renewed, the engine is asked again before anything is sent, and says whether to exchange again, move or end. */
    private suspend fun renew(
        first: RetryStep.Again,
        asked: Asked,
        ctx: PostContext,
        state: RetryState,
    ): LoopStep<Nothing> {
        state.ledger.took(first, asked.cause)
        if (first.afterMs > 0L) ctx.timedBackoff { state.retry.waits.wait(first.afterMs, asked.cause, asked.at) }
        val outcome = exchange(ctx, asked.failed)
        state.ledger.exchanged(outcome)
        if (outcome == Exchange.RENEWED) return LoopStep.Continue
        return take(next(asked.cause, asked.at, ctx, state), asked, ctx, state)
    }

    /** A renewal with no answer behind it names no dead credential: only an answer the reader read can. One exchange
     *  per step: a token endpoint that could not answer (a 429, a 5xx or a send that threw) is unavailable, which the
     *  engine asks again 2 s and then 4 s later; any other failure refused the login. */
    private suspend fun exchange(ctx: PostContext, failed: RetryOutcome.Failed?): Exchange {
        if (!ctx.auth.allowRefreshAfterFailure(failed?.deadCredential == true)) return Exchange.REFUSED
        val perf = ctx.observers.perf
        perf?.add(PerfKeys.REFRESHES, 1)
        return when (TurnPerfTiming.timedOr(perf, PerfKeys.REFRESH_MS) { ctx.auth.exchange() }) {
            is RefreshOutcome.Refreshed -> Exchange.RENEWED.also { ctx.observers.authRefreshObserver() }
            is RefreshOutcome.Unavailable, is RefreshOutcome.TransportFailed -> Exchange.UNAVAILABLE
            RefreshOutcome.NoCredentialsFile, RefreshOutcome.NoRefreshToken, is RefreshOutcome.Rejected,
            is RefreshOutcome.ReadFailed, is RefreshOutcome.PersistFailed,
            -> Exchange.REFUSED
        }
    }

    /** The dialect's own repair of the body it built, once per turn: null when it has none for this answer, or the
     *  turn already spent its one amendment. */
    private fun amended(
        ctx: PostContext,
        failed: RetryOutcome.Failed,
        bodyJson: String,
        state: RetryState,
    ): LoopStep.Amend? {
        val status = failed.evidence.status.takeIf { state.ledger.count(Spend.AMEND) < MAX_AMENDS } ?: return null
        val amended = ctx.recovery.amendBodyOnFailure(status, failed.evidence.body.orEmpty(), bodyJson) ?: return null
        state.ledger.took(RetryStep.Again(0L, How.AMEND_REASONING), failed.cause)
        ctx.onRetry("amending request body after $status and retrying once")
        return LoopStep.Amend(amended)
    }

    /** The log's line for a send that threw: a stream torn after its handoff as a reissue, a path that broke before
     *  any answer by its [phase], a send that may have reached the provider named a possible duplicate. */
    private fun noteThrown(e: Throwable, ctx: PostContext, state: RetryState, phase: TransportFailurePhase?) {
        val n = "${state.ledger.attempts}/$MAX_ATTEMPTS_OUTSIDE_HOLDS"
        val line = TransportFaultLine.of(e, ctx.url).take(ERR_SNIPPET)
        val note = when (phase) {
            null -> "stream torn before first client frame, reissue $n: ${e::class.simpleName} $line"
            TransportFailurePhase.POST_SEND -> "transport-possible-duplicate ${e::class.simpleName} attempt $n: $line"
            TransportFailurePhase.CONNECT -> "transport ${e::class.simpleName} attempt $n: $line"
        }
        ctx.onRetry(note)
    }

    /** The log's line for a failed answer, in the words the doctor report masks the provider's body after
     *  (DoctorLogPayloads): an answer sent again by its attempt, and one that is not by what it was read as. A body
     *  that quotes a header of its own loses that value here (fix 4, Oct 27: a refusal that quotes the request's
     *  headers would otherwise write the turn state into the daemon log). */
    private fun noteFailed(ctx: PostContext, failed: RetryOutcome.Failed, step: RetryStep, state: RetryState) {
        val snippet = HeaderRedaction.redactText(failed.evidence.body.orEmpty()).take(ERR_SNIPPET)
        val stale = (failed.cause as? FailureCause.Provider.Rejected)?.staleReasoning == true
        val status = failed.evidence.status
        val line = when {
            step is RetryStep.Again ->
                "upstream $status attempt ${state.ledger.attempts}/$MAX_ATTEMPTS_OUTSIDE_HOLDS: $snippet"
            stale -> "upstream rejected this turn's stale reasoning, an encrypted_content error (no retry): $snippet"
            else -> "upstream $status ${failed.cause} (no retry: ${stepWords(step)}): $snippet"
        }
        ctx.onRetry(line)
    }

    /** What a step that sends nothing again does with the post, in the log's words. */
    private fun stepWords(step: RetryStep): String = when (step) {
        is RetryStep.Again -> "sent again"
        RetryStep.SwitchLogin -> "moved to another login"
        is RetryStep.Hold -> "held for its reset"
        is RetryStep.End -> "ended"
        RetryStep.Keep -> "kept"
    }

    /** One failure asked of the engine: the cause it was asked as, where the turn stood, and the answer it read,
     *  which a transport error and a torn stream have none of. */
    private data class Asked(val cause: FailureCause, val at: Position, val failed: RetryOutcome.Failed? = null)
}

/** A stream that ended before it carried an event the client saw: sent fresh, as section 8's table sends one. */
private val TORN_BEFORE_CLIENT = FailureCause.Path.Torn(Tear.NO_EVENTS, toolOpen = false)
