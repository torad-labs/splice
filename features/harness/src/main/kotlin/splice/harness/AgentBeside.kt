// NEW: the agent's call (docs/specs/harness.md, decision 12, Beside the client's calls). One message of the model calls
// a capability's tool and tools of the client's: the client gets its own calls and runs them, while the capability's
// worker runs here. When the client sends its results, its request waits for the capability's, held, and carries them
// beside the client's; a failure that fails closed, or a refusal from a worker that may refuse, refuses that request,
// and the call is left out from then on.
package splice.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import splice.core.capability.Capability
import splice.core.failure.Decline
import splice.harness.agent.AgentTool
import splice.harness.agent.AgentToolPort
import splice.harness.agent.AgentWire
import splice.harness.agent.Answered
import splice.harness.agent.ModelCall
import splice.harness.agent.Settled
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What a request's kept calls come to: every result it is owed, or the refusal of the request. */
internal sealed class Owed {
    data class Paid(val matches: List<Match>) : Owed()

    data class Refused(val why: Decline) : Owed()
}

internal class AgentBeside(
    private val capabilities: List<Capability>,
    /** The tools beside the capabilities, which a kept call of one of them is answered by. */
    private val port: AgentToolPort,
    private val records: AgentRecords,
    private val answers: AgentAnswers,
    /** The scope a worker runs in once the turn that called it has ended: the head's harness's own. */
    private val scope: CoroutineScope,
) {
    private val wire = AgentWire()

    /** The work of each kept call made beside the client's, by the call's key, while it runs. */
    private val working = ConcurrentHashMap<String, Working>()

    /** Keeps [call] for [session] and starts the work of [asked], the calls it made beside the client's. It returns
     *  once every worker has been asked, or its work has ended without asking it: the client gets its own calls only
     *  then, so the workers run while the client runs them. */
    suspend fun start(session: String, call: KeptCall, asked: List<ModelCall>, on: CallFor) {
        val sent = CompletableDeferred<Unit>()
        val left = AtomicInteger(asked.size)
        // no turn holds the work yet: what it says is said once the client's next request waits for it
        val voices = answers.voices(asked, on)
        val work = scope.async {
            answers.settle(asked, on, voices) { if (left.decrementAndGet() == 0) sent.complete(Unit) }
        }
        work.invokeOnCompletion { sent.complete(Unit) }
        working[call.key] = Working(work, voices)
        records.put(session, call)
        sent.await()
    }

    /** [matches] with every result made beside the client's that has not come yet, waited for while [on]'s turn is
     *  held. A call whose work is gone (the daemon restarted while it ran) failed; one whose capability is no longer
     *  declared is left out, its message going as the client holds it. */
    suspend fun paid(session: String, matches: List<Match>, on: CallFor): Owed {
        val paid = matches.mapNotNull { match ->
            val call = match.call
            if (!call.waiting) return@mapNotNull match
            val asked = asked(call, on)
                ?: return@mapNotNull null.also { records.put(session, call.copy(leftOut = true)) }
            val work = working[call.key]
            val settled = if (work == null) {
                asked.map { Settled.Failed(LOST) }
            } else {
                answers.held(asked, on, work.voices) { work.settled.await() }.also { working.remove(call.key, work) }
            }
            when (val answered = answers.answered(asked, settled)) {
                is Answered.Refused -> {
                    records.put(session, call.copy(leftOut = true))
                    return Owed.Refused(answered.why)
                }
                is Answered.Results -> Match(match.at, kept(session, call, answered.blocks))
            }
        }
        return Owed.Paid(paid)
    }

    /** [call] with [blocks], its results, kept for the requests after this one. */
    private fun kept(session: String, call: KeptCall, blocks: List<JsonObject>): KeptCall {
        val byId = blocks.associateBy { wire.resultFor(it) }
        return call.copy(beside = call.beside.map { it.copy(block = byId[it.id]) }).also { records.put(session, it) }
    }

    /** The calls [call] made beside the client's, as the model made them; null when a capability among them is no
     *  longer declared. */
    private fun asked(call: KeptCall, on: CallFor): List<ModelCall>? {
        val made = call.messages.lastOrNull()?.let(wire::content).orEmpty().filter(wire::isCall)
            .associateBy { wire.callId(it) }
        val session = on.agentSession()
        val ported = session?.let { port.offer(it).tools }.orEmpty()
        return call.beside.map { result ->
            val capability = capabilities.firstOrNull { it.name == result.capability }
            val offered = ported.firstOrNull { it.name == result.capability }
            val tool = capability?.let { AgentTool.OfCapability(it) }
                ?: offered?.let { AgentTool.OfPort(it, port, checkNotNull(session)) }
                ?: return null
            ModelCall(result.id, tool, made[result.id]?.get(INPUT) ?: JsonObject(emptyMap()))
        }
    }
}

/** The work of the calls one message made beside the client's, and what each says through. */
private data class Working(val settled: Deferred<List<Settled>>, val voices: List<HeldVoice>)

/** why: what the model is told of a call whose worker's run did not outlive a restart of the daemon. */
private const val LOST = "its work was lost when splice restarted"
private const val INPUT = "input"
