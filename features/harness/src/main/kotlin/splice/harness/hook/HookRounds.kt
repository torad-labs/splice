// NEW: modes hooks Phase 2 (modes/README.md, "Hooks"): round-level interceptor for `response` and `on_stop` hooks.
// Composes cleanly with an inner RoundInterceptor (AgentRounds) when both are active on a turn, and is attached ONLY
// when the session has at least one `response` or `on_stop` hook (zero overhead otherwise).
package splice.harness.hook

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.parse.lenientJson
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.harness.AgentTyped
import splice.harness.agent.AgentAsk
import splice.harness.agent.AgentSession
import splice.harness.agent.AgentWire
import splice.harness.agent.USER
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
    private val next: AgentAsk,
) : RoundInterceptor, RoundBodyInterceptor {
    private val wire = AgentWire()
    private val emit = HookRewriteEmit()

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

    private suspend fun stepRound(
        state: LoopState,
        sink: WireSink,
        postRound: InterceptedRoundPost,
    ): Step {
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
        decision: Decision,
        state: LoopState,
        buffer: HookBufferSink,
        sink: WireSink,
        outcome: TurnOutcome.Success,
    ): Step = when (decision) {
        is Decision.Refuse -> Step.Done(refused(decision.mode, decision.reason))
        is Decision.Continue -> {
            state.continues++
            val nextBody = continueBody(state.current, state.produced, buffer.message.blocks(), decision.text)
            if (nextBody != null) {
                Step.Again(nextBody)
            } else {
                buffer.replayInto(sink)
                Step.Done(RoundResult.Outcome(outcome))
            }
        }
        is Decision.Replace -> {
            sink.addTextBlock(decision.text)
            Step.Done(RoundResult.Outcome(outcome))
        }
        is Decision.Rewrite -> {
            emit.emit(sink, decision.body)
            Step.Done(RoundResult.Outcome(outcome))
        }
        Decision.Pass -> {
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

    private suspend fun decide(blocks: List<JsonObject>, continues: Int): Decision {
        val resp = hooks.response(session, blocks)
        if (resp is HookRuns.ResponseDecision.Refused) return Decision.Refuse(resp.mode, resp.reason)
        val rewritten = (resp as? HookRuns.ResponseDecision.Rewritten)?.body
        val currentBlocks = (rewritten?.get("content") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: blocks
        val stopDecision = if (currentBlocks.none(wire::isCall)) checkStop(currentBlocks, continues) else null
        return stopDecision ?: rewritten?.let(Decision::Rewrite) ?: Decision.Pass
    }

    private suspend fun checkStop(blocks: List<JsonObject>, continues: Int): Decision? =
        when (val stop = hooks.onStop(session, blocks, emit.textOf(blocks))) {
            is HookRuns.StopDecision.Refused -> Decision.Refuse(stop.mode, stop.reason)
            is HookRuns.StopDecision.Continue ->
                if (continues < MAX_ON_STOP_CONTINUES) Decision.Continue(stop.text) else null
            is HookRuns.StopDecision.Replace -> Decision.Replace(stop.text)
            HookRuns.StopDecision.Pass -> null
        }

    private fun continueBody(
        body: RoundBody,
        produced: MutableList<JsonObject>,
        assistantBlocks: List<JsonObject>,
        followUp: String,
    ): RoundBody? {
        val userBlock = buildJsonObject {
            put("type", "text")
            put("text", JsonPrimitive(followUp))
        }
        val made = listOf(wire.message(wire.assistant, assistantBlocks), wire.message(USER, listOf(userBlock)))
        val reqObj = (body as? RoundBody.Tree)?.element as? JsonObject
            ?: JsonScalars.objectOrNull(lenientJson, body.text)
        val built = next.next(reqObj, produced + made, made) ?: return null
        produced += made
        return RoundBody.Tree(built)
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

    private sealed class Decision {
        data object Pass : Decision()
        data class Rewrite(val body: JsonObject) : Decision()
        data class Replace(val text: String) : Decision()
        data class Continue(val text: String) : Decision()
        data class Refuse(val mode: String, val reason: String) : Decision()
    }
}
