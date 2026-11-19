// NEW: the agent's call (docs/specs/harness.md, decisions 6 and 12). The calls one message of the model made of the
// capabilities' tools, worked while the turn is held, and what they come to: the text each worker returned as the
// call's result, a failure the model is told of where the capability fails open, or a refusal of the turn where it
// fails closed or its worker may refuse and did.
package splice.harness

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.capability.Capability
import splice.core.capability.CapabilityFailure
import splice.core.failure.Decline
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.port.Held

/** One call of a capability's tool the model made: its id, the capability, and the input it gave the tool. */
internal data class ModelCall(val id: String, val capability: Capability, val input: JsonElement)

/** What one call's work came to. */
internal sealed class Settled {
    data class Text(val text: String) : Settled()

    /** The work could not finish: [why] never holds a key. */
    data class Failed(val why: String) : Settled()

    /** A worker that may refuse refused the turn, with its own [reason]. */
    data class Refused(val reason: String) : Settled()

    /** The head stopped while it worked: the turn goes on as today, whatever the capability's `on_failure`. */
    data object Stopped : Settled()
}

/** What one message's calls come to: their results in the order they were made, or the refusal that ends the turn. */
internal sealed class Answered {
    data class Results(val blocks: List<JsonObject>) : Answered()

    data class Refused(val why: Decline) : Answered()
}

internal class AgentAnswers(
    private val head: String,
    private val calls: CapabilityCall,
    private val stops: AgentStops,
    private val log: LogSink,
    private val clock: ElapsedClock,
) {
    private val wire = AgentWire()

    /** Works [asked] for the turn [on] names while it is held, and answers what they come to. */
    suspend fun answer(asked: List<ModelCall>, on: CallFor): Answered {
        val voices = voices(asked, on)
        return answered(asked, held(asked, on, voices) { settle(asked, on, voices) })
    }

    /** What each call of [asked] says through, on the turn that holds it: its capability's message, then its worker's
     *  progress (decision 7). */
    fun voices(asked: List<ModelCall>, on: CallFor): List<HeldVoice> = asked.map { calls.voice(it.capability, on) }

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
            on.turn.release(Held(asked.map { it.capability.name }.distinct(), clock() - startedMs))
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

    private suspend fun settled(asked: ModelCall, on: CallFor, voice: HeldVoice, posted: WorkerPosted): Settled {
        val capability = asked.capability
        return when (val end = calls.work(capability, on.copy(input = asked.input), posted, voice)) {
            is WorkerEnd.Result -> Settled.Text(end.text)
            is WorkerEnd.Refusal -> if (capability.result.mayRefuse) {
                Settled.Refused(end.reason)
            } else {
                Settled.Failed("it refused the turn, which it may not")
            }
            is WorkerEnd.Failed -> Settled.Failed(end.why)
        }
    }

    /** The results of [asked], each one's from [settled] at the same place, or the first refusal among them. */
    fun answered(asked: List<ModelCall>, settled: List<Settled>): Answered {
        val blocks = asked.zip(settled).map { (call, end) ->
            when (val result = result(call, end)) {
                is Answer.Block -> result.block
                is Answer.Refusal -> return Answered.Refused(result.why)
            }
        }
        return Answered.Results(blocks)
    }

    private fun result(asked: ModelCall, end: Settled): Answer {
        val name = asked.capability.name
        return when (end) {
            is Settled.Text -> Answer.Block(wire.result(asked.id, end.text, error = false))
            is Settled.Refused -> Answer.Refusal(Decline.CapabilityRefused(name, end.reason)).also {
                log("[$head] capability $name refused the turn the model called it in: ${end.reason}\n")
            }
            is Settled.Failed -> failed(asked, end.why)
            Settled.Stopped -> {
                log("[$head] capability $name stopped: $DRAIN_STOPPED; the model is told\n")
                Answer.Block(wire.result(asked.id, "The capability $name failed: $DRAIN_STOPPED.", error = true))
            }
        }
    }

    /** Closed, the turn is refused; open, the model is told the capability failed and why, and goes on. */
    private fun failed(asked: ModelCall, why: String): Answer {
        val name = asked.capability.name
        if (asked.capability.limits.onFailure == CapabilityFailure.CLOSED) {
            log("[$head] capability $name failed: $why; the turn is refused\n")
            return Answer.Refusal(Decline.CapabilityFailed(name, why))
        }
        log("[$head] capability $name failed: $why; the model is told and goes on\n")
        return Answer.Block(wire.result(asked.id, "The capability $name failed: $why.", error = true))
    }

    /** One call's part of the answer. */
    private sealed class Answer {
        data class Block(val block: JsonObject) : Answer()

        data class Refusal(val why: Decline) : Answer()
    }
}

/** why: what the model and the log are told of a call whose work the head's stop ended. */
private const val DRAIN_STOPPED = "splice stopped its work while the head drained"
