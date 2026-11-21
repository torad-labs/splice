// NEW: Nov 21, 2026 — split from AgentAnswers.kt along its seam (docs/specs/harness.md, decisions 6 and 12): what a
// call of the model's is, what its work came to, and what a message's settled calls read as. AgentAnswers works the
// calls while the turn is held; this says what they come to: each one's result in the order the calls were made, a
// failure the model is told of where the capability fails open, or the refusal that ends the turn where it fails
// closed or its worker may refuse and did.
package splice.harness.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.capability.CapabilityFailure
import splice.core.failure.Decline
import splice.core.util.LogSink

/** One call the model made of a tool splice answers: its id, the tool, and the input it gave the tool. */
internal data class ModelCall(val id: String, val tool: AgentTool, val input: JsonElement)

/** What one call's work came to. */
internal sealed class Settled {
    data class Text(val text: String) : Settled()

    /** A port's tool answered with an error the model reads as it is. */
    data class Error(val text: String) : Settled()

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

/** What the settled calls of one message read as, on the head [head]. */
internal class AgentResults(private val head: String, private val log: LogSink) {
    private val wire = AgentWire()

    /** The results of [asked], each one's from [settled] at the same place, or the first refusal among them. */
    fun of(asked: List<ModelCall>, settled: List<Settled>): Answered {
        val blocks = asked.zip(settled).map { (call, end) ->
            when (val result = result(call, end)) {
                is Answer.Block -> result.block
                is Answer.Refusal -> return Answered.Refused(result.why)
            }
        }
        return Answered.Results(blocks)
    }

    private fun result(asked: ModelCall, end: Settled): Answer {
        val name = asked.tool.label
        return when (end) {
            is Settled.Text -> Answer.Block(wire.result(asked.id, end.text, error = false))
            is Settled.Error -> Answer.Block(wire.result(asked.id, end.text, error = true))
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
        val name = asked.tool.label
        val closed = (asked.tool as? AgentTool.OfCapability)?.capability?.limits?.onFailure == CapabilityFailure.CLOSED
        if (closed) {
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
