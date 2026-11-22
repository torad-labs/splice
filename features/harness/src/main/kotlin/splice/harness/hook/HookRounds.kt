// NEW: modes hooks Phase 2 (modes/README.md, "Hooks"): round-level interceptor for `response` and `on_stop` hooks.
// Composes cleanly with an inner RoundInterceptor (AgentRounds) when both are active on a turn, and is attached ONLY
// when the session has at least one `response` or `on_stop` hook (zero overhead otherwise).
package splice.harness.hook

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.turn.TurnOutcome
import splice.harness.AgentTyped
import splice.harness.agent.AgentAsk
import splice.harness.agent.AgentSession
import splice.harness.agent.AgentWire
import splice.upstream.InterceptedRoundPost
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundBodyInterceptor
import splice.upstream.RoundBodyPost
import splice.upstream.RoundInterceptor
import splice.upstream.RoundResult
import splice.upstream.sse.WireSink
import splice.upstream.transport.TurnDeclined

// why: bounds `on_stop` Continue loops so a misbehaving hook cannot spin upstream calls indefinitely on one turn.
private const val MAX_ON_STOP_CONTINUES = 8

internal class HookRounds(
    private val session: AgentSession,
    private val hooks: HookRuns,
    private val inner: RoundInterceptor?,
    next: AgentAsk,
) : RoundInterceptor, RoundBodyInterceptor {
    private val wire = AgentWire()
    private val emit = HookRewriteEmit()
    private val cont = HookContinue(wire, next)

    override suspend fun intercept(bodyJson: String, sink: WireSink, postRound: InterceptedRoundPost): RoundResult =
        intercept(RoundBody.Text(bodyJson), sink, postRound)

    override suspend fun intercept(body: RoundBody, sink: WireSink, postRound: InterceptedRoundPost): RoundResult {
        val state = LoopState(body)
        while (true) {
            when (val step = stepRound(state, sink, postRound)) {
                is Step.Done -> return step.result
                is Step.Again -> state.current = step.body
            }
        }
    }

    private suspend fun stepRound(state: LoopState, sink: WireSink, postRound: InterceptedRoundPost): Step {
        val buffer = HookBufferSink()
        val posted = invokeInner(state.current, AgentTyped(buffer, buffer.message), postRound)
        val outcome = (posted as? RoundResult.Outcome)?.outcome as? TurnOutcome.Success
        if (outcome == null || outcome.incomplete) {
            buffer.replayInto(sink)
            return Step.Done(posted)
        }
        state.totalOutput += outcome.usage.outputTokens
        val finalOutcome = outcome.copy(usage = outcome.usage.copy(outputTokens = state.totalOutput))
        return applyDecision(decide(buffer.message.blocks(), state.continues), state, buffer, sink, finalOutcome)
    }

    private suspend fun applyDecision(
        decision: RoundDecision,
        state: LoopState,
        buffer: HookBufferSink,
        sink: WireSink,
        outcome: TurnOutcome.Success,
    ): Step = when (decision) {
        is RoundDecision.Refuse -> Step.Done(refused(decision.mode, decision.reason))
        is RoundDecision.Continue -> {
            state.continues++
            val nextBody = cont.build(state.current, state.produced, buffer.message.blocks(), decision.text)
            if (nextBody != null) {
                Step.Again(nextBody)
            } else {
                buffer.replayInto(sink)
                Step.Done(RoundResult.Outcome(outcome))
            }
        }
        is RoundDecision.Replace -> {
            sink.addTextBlock(decision.text)
            Step.Done(RoundResult.Outcome(outcome))
        }
        is RoundDecision.Rewrite -> {
            emit.emit(sink, decision.body)
            Step.Done(RoundResult.Outcome(outcome))
        }
        RoundDecision.Pass -> {
            buffer.replayInto(sink)
            Step.Done(RoundResult.Outcome(outcome))
        }
    }

    private suspend fun invokeInner(body: RoundBody, sink: WireSink, postRound: InterceptedRoundPost): RoundResult =
        when (inner) {
            is RoundBodyInterceptor -> inner.intercept(body, sink, postRound)
            null -> postDirectly(postRound, body, sink)
            else -> inner.intercept(body.text, sink, postRound)
        }

    private suspend fun postDirectly(post: InterceptedRoundPost, body: RoundBody, sink: WireSink): RoundResult =
        when (post) {
            is RoundBodyPost -> post.postInto(body, sink)
            is RedirectableRoundPost -> post.into(body.text, sink)
            else -> post(body.text)
        }

    private suspend fun decide(blocks: List<JsonObject>, continues: Int): RoundDecision {
        val resp = hooks.response(session, blocks)
        if (resp is HookRuns.ResponseDecision.Refused) return RoundDecision.Refuse(resp.mode, resp.reason)
        val rewritten = (resp as? HookRuns.ResponseDecision.Rewritten)?.body
        val currentBlocks = (rewritten?.get("content") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: blocks
        val stopDecision = if (currentBlocks.none(wire::isCall)) checkStop(currentBlocks, continues) else null
        return stopDecision ?: rewritten?.let(RoundDecision::Rewrite) ?: RoundDecision.Pass
    }

    private suspend fun checkStop(blocks: List<JsonObject>, continues: Int): RoundDecision? =
        when (val stop = hooks.onStop(session, blocks, emit.textOf(blocks))) {
            is HookRuns.StopDecision.Refused -> RoundDecision.Refuse(stop.mode, stop.reason)
            is HookRuns.StopDecision.Continue ->
                if (continues < MAX_ON_STOP_CONTINUES) RoundDecision.Continue(stop.text) else null
            is HookRuns.StopDecision.Replace -> RoundDecision.Replace(stop.text)
            HookRuns.StopDecision.Pass -> null
        }

    private fun refused(mode: String, reason: String): RoundResult =
        RoundResult.Ended(TurnDeclined(FailureCause.Splice.Declined(Decline.ModeRefused(mode, reason))))

    private data class LoopState(
        var current: RoundBody,
        val produced: MutableList<JsonObject> = mutableListOf(),
        var totalOutput: Long = 0L,
        var continues: Int = 0,
    )

    private sealed class Step {
        data class Done(val result: RoundResult) : Step()
        data class Again(val body: RoundBody) : Step()
    }
}
