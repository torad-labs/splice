// NEW: the agent's call (docs/specs/harness.md, decisions 6 and 12). The calls one message of the model made of the
// capabilities' tools, worked while the turn is held, and what they come to: the text each worker returned as the
// call's result, a failure the model is told of where the capability fails open, or a refusal of the turn where it
// fails closed or its worker may refuse and did.
package splice.harness

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.capability.Capability
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.harness.agent.AgentResults
import splice.harness.agent.AgentTool
import splice.harness.agent.Answered
import splice.harness.agent.ModelCall
import splice.harness.agent.Settled
import splice.head.port.Held
import splice.upstream.codemode.ProcessDispatchers

internal class AgentAnswers(
    private val head: String,
    private val calls: CapabilityCall,
    private val stops: AgentStops,
    private val log: LogSink,
    private val clock: ElapsedClock,
) {
    private val results = AgentResults(head, log)

    /** Works [asked] for the turn [on] names while it is held, and answers what they come to. */
    suspend fun answer(asked: List<ModelCall>, on: CallFor): Answered {
        val voices = voices(asked, on)
        return answered(asked, held(asked, on, voices) { settle(asked, on, voices) })
    }

    /** What each call of [asked] says through, on the turn that holds it: its capability's message, then its worker's
     *  progress (decision 7). */
    fun voices(asked: List<ModelCall>, on: CallFor): List<HeldVoice> = asked.map { call ->
        when (val tool = call.tool) {
            is AgentTool.OfCapability -> calls.voice(tool.capability, on)
            is AgentTool.OfPort -> HeldVoice(tool.label, PORT_COLOR)
        }
    }

    /** Runs [work] with [on]'s turn held, where it can be, saying the capabilities of [asked] held it; while it holds,
     *  the turn says what [voices] say. */
    suspend fun held(asked: List<ModelCall>, on: CallFor, voices: List<HeldVoice>, work: CallsWork): List<Settled> {
        if (!on.turn.holdable) return work()
        val startedMs = clock()
        on.turn.hold()
        voices.forEach { it.heldBy(on.turn) }
        try {
            return work()
        } finally {
            on.turn.release(Held(asked.map { it.tool.label }.distinct(), clock() - startedMs))
        }
    }

    /** Each call's work, all at once, each within its own time limit, each speaking through its own of [voices]; every
     *  one stopped when the head stops. [posted] hears each worker's request sent. */
    suspend fun settle(
        asked: List<ModelCall>,
        on: CallFor,
        voices: List<HeldVoice>,
        posted: WorkerPosted = WorkerPosted { },
    ): List<Settled> = stops.raced {
        coroutineScope {
            asked.zip(voices).map { (call, voice) -> async { settled(call, on, voice, posted) } }.awaitAll()
        }
    } ?: asked.map { Settled.Stopped }

    private suspend fun settled(asked: ModelCall, on: CallFor, voice: HeldVoice, posted: WorkerPosted): Settled =
        when (val tool = asked.tool) {
            is AgentTool.OfPort -> ported(tool, asked.input, posted)
            is AgentTool.OfCapability -> worked(tool.capability, asked, on, voice, posted)
        }

    /** A port's tool answers in the daemon's own thread pool; its answer is the call's result. */
    private suspend fun ported(tool: AgentTool.OfPort, input: JsonElement, posted: WorkerPosted): Settled {
        posted.posted()
        val answer = withContext(ProcessDispatchers().io()) {
            tool.port.answer(tool.offered.name, input as? JsonObject ?: JsonObject(emptyMap()), tool.session)
        }
        return if (answer.error) Settled.Error(answer.text) else Settled.Text(answer.text)
    }

    private suspend fun worked(
        capability: Capability,
        asked: ModelCall,
        on: CallFor,
        voice: HeldVoice,
        posted: WorkerPosted,
    ): Settled = when (val end = calls.work(capability, on.copy(input = asked.input), posted, voice)) {
        is WorkerEnd.Result -> Settled.Text(end.text)
        is WorkerEnd.Refusal -> if (capability.result.mayRefuse) {
            Settled.Refused(end.reason)
        } else {
            Settled.Failed("it refused the turn, which it may not")
        }
        is WorkerEnd.Failed -> Settled.Failed(end.why)
    }

    /** The results of [asked], each one's from [settled] at the same place, or the first refusal among them. */
    fun answered(asked: List<ModelCall>, settled: List<Settled>): Answered = results.of(asked, settled)
}

/** why: a port's tool names no colour of its own; a held turn on one says its label in the capabilities' default. */
private const val PORT_COLOR = "cyan"
