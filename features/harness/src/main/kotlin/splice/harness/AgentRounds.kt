// NEW: the agent's call (docs/specs/harness.md, decision 12, Answered and Beside the client's calls). Every round of a
// turn whose request carries a capability's tool passes through here. The client is never shown a call of that tool.
// A message that calls it and nothing else is answered while the turn is held: the provider is sent the same request
// with the model's message and the worker's text as the call's result, and its answer goes on as the rest of the same
// response, as many times as the answer calls it again. The response ends once, with the last answer's ending, and its
// usage is the last request's input and cache counts with every round's output. What the provider produced is kept
// before the response ends. A message that calls it beside the client's tools ends as the client's calls, and the
// worker runs while the client runs them.
package splice.harness

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.parse.lenientJson
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.JsonScalars
import splice.harness.agent.AgentAsk
import splice.harness.agent.AgentTool
import splice.upstream.InterceptedRoundPost
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundBodyInterceptor
import splice.upstream.RoundBodyPost
import splice.upstream.RoundInterceptor
import splice.upstream.RoundResult
import splice.upstream.sse.WireSink
import splice.upstream.transport.TurnDeclined

/** The rounds of one request of [session], whose tools splice answers are [tools] by name, sent as [offered]. */
internal class AgentRounds(
    private val session: String,
    private val tools: Map<String, AgentTool>,
    private val offered: List<JsonObject>,
    private val on: CallFor,
    private val answers: AgentAnswers,
    private val keep: AgentKeep,
    /** Where the request after an answered call comes from. */
    private val next: AgentAsk,
) : RoundInterceptor, RoundBodyInterceptor {
    private val wire = AgentWire()
    private val marks = CacheMarks()

    override suspend fun intercept(bodyJson: String, sink: WireSink, postRound: InterceptedRoundPost): RoundResult =
        intercept(RoundBody.Text(bodyJson), sink, postRound)

    override suspend fun intercept(body: RoundBody, sink: WireSink, postRound: InterceptedRoundPost): RoundResult {
        val turn = SoFar()
        var round = body
        while (true) {
            when (val step = round(turn, round, sink, postRound)) {
                is Step.Ended -> return step.result
                is Step.Again -> round = step.body
            }
        }
    }

    /** One round of the turn: it ends the turn, or its calls are answered and the provider is asked again. */
    private suspend fun round(turn: SoFar, body: RoundBody, sink: WireSink, postRound: InterceptedRoundPost): Step {
        val seen = AgentSink(sink, tools.keys)
        val posted = post(postRound, body, AgentTyped(seen, seen.message))
        val outcome = (posted as? RoundResult.Outcome)?.outcome as? TurnOutcome.Success
            ?: return Step.Ended(failed(turn, posted))
        val made = Made(seen.message.blocks(), outcome, posted)
        turn.took(seen.message.shown(), outcome.usage)
        val asked = asked(made.message)
        return when {
            asked.isEmpty() || outcome.incomplete -> Step.Ended(ended(turn, made))
            made.message.any(::clients) -> Step.Ended(beside(turn, made, asked))
            else -> answered(turn, body, made.message, answers.answer(asked, on)) ?: Step.Ended(ended(turn, made))
        }
    }

    /** A round that did not succeed ends the turn as it ended; after an answered call it is not carried on. */
    private fun failed(turn: SoFar, posted: RoundResult): RoundResult =
        if (turn.answered) unresumable(posted) else posted

    /** Whether [block] is a call of one of the client's own tools. */
    private fun clients(block: JsonObject): Boolean = wire.isCall(block) && wire.callName(block) !in tools

    /** The calls' results go to the provider with the model's message, as the same request again; a refusal ends it.
     *  Null when the next request cannot be made, and the round ends the turn. */
    private fun answered(turn: SoFar, body: RoundBody, message: List<JsonObject>, answered: Answered): Step? =
        when (answered) {
            is Answered.Refused ->
                Step.Ended(RoundResult.Ended(TurnDeclined(FailureCause.Splice.Declined(answered.why))))
            is Answered.Results -> {
                val made = listOf(
                    wire.message(wire.assistant, message),
                    wire.message(USER, answered.blocks),
                )
                next.next(request(body), turn.produced + made, made)?.let { request ->
                    turn.produced += made
                    Step.Again(RoundBody.Tree(request))
                }
            }
        }

    /** The round's request as a tree, to send again with the call's result. */
    private fun request(body: RoundBody): JsonObject? =
        (body as? RoundBody.Tree)?.element as? JsonObject ?: JsonScalars.objectOrNull(lenientJson, body.text)

    /** The turn's round into [sink]: a post that cannot be pointed at a sink of its own goes to the client as it is. */
    private suspend fun post(post: InterceptedRoundPost, body: RoundBody, sink: WireSink): RoundResult = when (post) {
        is RoundBodyPost -> post.postInto(body, sink)
        is RedirectableRoundPost -> post.into(body.text, sink)
        else -> post(body.text)
    }

    /** The calls of [message] of the capabilities' tools, in the order the model made them. */
    private fun asked(message: List<JsonObject>): List<ModelCall> = message.filter(wire::isCall).mapNotNull { block ->
        val tool = tools[wire.callName(block)] ?: return@mapNotNull null
        val id = wire.callId(block) ?: return@mapNotNull null
        ModelCall(id, tool, block[INPUT] ?: JsonObject(emptyMap()))
    }

    /** The last round ended the turn. When a call was answered on the way, what the provider produced is kept first. */
    private fun ended(turn: SoFar, made: Made): RoundResult {
        if (!turn.answered) return made.posted
        val produced = turn.produced + wire.message(wire.assistant, made.message)
        keep.put(session, KeptCall(marks.key(turn.client), produced, offered))
        return RoundResult.Outcome(made.outcome.copy(usage = turn.usage(made.outcome.usage)))
    }

    /** The message calls the capability beside the client's tools: the client gets its calls, and the worker starts. */
    private suspend fun beside(turn: SoFar, made: Made, asked: List<ModelCall>): RoundResult {
        val call = KeptCall(
            marks.key(turn.client),
            turn.produced + wire.message(wire.assistant, made.message),
            offered,
            beside = asked.map { KeptResult(it.id, it.tool.name) },
        )
        keep.beside(session, call, asked, on)
        return RoundResult.Outcome(made.outcome.copy(usage = turn.usage(made.outcome.usage)))
    }

    /** What one round came to: the model's whole message, its successful outcome, and the result as posted. */
    private data class Made(val message: List<JsonObject>, val outcome: TurnOutcome.Success, val posted: RoundResult)

    /** Where a round leaves the turn: ended with [Ended.result], or asked again with [Again.body]. */
    private sealed class Step {
        class Ended(val result: RoundResult) : Step()

        class Again(val body: RoundBody) : Step()
    }

    /** A round after an answered call failed: it is not carried on from its partial output, which a continuation would
     *  put after the turn's first request and not after the call. Its usage still counts. */
    private fun unresumable(posted: RoundResult): RoundResult {
        val failure = (posted as? RoundResult.Outcome)?.outcome as? TurnOutcome.Failure ?: return posted
        val partial = failure.partial ?: return posted
        return RoundResult.Outcome(failure.copy(partial = null, salvagedUsage = failure.salvagedUsage + partial.usage))
    }

    /** The turn so far: the blocks the client was shown, the messages the provider produced before the last, and the
     *  output of every round. */
    private class SoFar {
        val client = mutableListOf<JsonObject>()
        val produced = mutableListOf<JsonObject>()
        private var output = 0L

        val answered: Boolean get() = produced.isNotEmpty()

        fun took(shown: List<JsonObject>, usage: Usage) {
            client += shown
            output += usage.outputTokens
        }

        /** The last request's input and cache counts, with the output of every round. */
        fun usage(last: Usage): Usage = last.copy(outputTokens = output)
    }
}

private const val INPUT = "input"
