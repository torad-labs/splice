// NEW: the agent's call (docs/specs/harness.md, decision 12) at one head. Before a request goes upstream, the calls
// the model made earlier in its session are put back where it made them (waiting, held, for a result owed beside the
// client's), the capabilities' tools its rule allows are offered after the client's tools, a tool a kept call was made
// of goes with it, and the request's rounds are answered through AgentRounds.
package splice.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.capability.Capability
import splice.core.capability.CapabilityTool
import splice.core.failure.Decline
import splice.head.port.HarnessRequest
import splice.head.port.HarnessTurn
import splice.upstream.RoundInterceptor

/** What the agent's call makes of one request. */
internal sealed class AgentEnd {
    /** The request goes as [sending] (null: as it was built), its rounds through [rounds] when it has any. */
    data class Go(val sending: JsonObject? = null, val rounds: RoundInterceptor? = null) : AgentEnd()

    /** A result owed beside the client's refused the request. */
    data class Refused(val why: Decline) : AgentEnd()
}

internal class AgentCalls(
    private val head: String,
    capabilities: List<Capability>,
    private val records: AgentRecords,
    private val answers: AgentAnswers,
    private val besides: AgentBeside,
    private val stops: AgentStops,
) {
    private val rules = CapabilityRules()
    private val wire = AgentWire()
    private val history = AgentHistory()
    private val keep = AgentKeep(records, besides)

    /** The capabilities on for this head the model may call, with the tool each is offered as, the same bytes on every
     *  request. Two heads' capabilities may offer one tool name; each head answers it with its own. */
    private val agents: List<Pair<Capability, JsonObject>> =
        capabilities.filter { it.onFor(head) }.mapNotNull { capability ->
            capability.call.tool?.takeIf { capability.call.byAgent }?.let { capability to offered(it) }
        }

    /** The head stops: the work of every call stops, and none is worked until [started]. */
    fun stop() {
        stops.stop()
    }

    fun started() {
        stops.started()
    }

    suspend fun around(request: HarnessRequest, turn: HarnessTurn): AgentEnd {
        val session = request.sessionId
        if (session == null || agents.isEmpty()) return AgentEnd.Go()
        val sent = turn.sending
        val on = CallFor(head, turn, request)
        val kept = when (val owed = besides.paid(session, history.matches(sent, records.of(session)), on)) {
            is Owed.Refused -> return AgentEnd.Refused(owed.why)
            is Owed.Paid -> owed.matches
        }
        val clients = wire.toolNames(sent)
        val offering = agents.filter { (capability, tool) ->
            rules.offers(capability, head, request) && name(tool) !in clients
        }
        val carried = (offering.map { it.second } + kept.flatMap { it.call.tools }).distinctBy(::name)
        val body = wire.withTools(history.restored(sent, kept), carried)
        val names = carried.mapNotNull(::name).toSet()
        val answering = agents.mapNotNull { (capability, tool) ->
            name(tool)?.takeIf { it in names }?.let { it to capability }
        }.toMap()
        val rounds = answering.takeIf { it.isNotEmpty() }?.let { AgentRounds(session, it, carried, on, answers, keep) }
        return AgentEnd.Go(body.takeUnless { it === sent }, rounds)
    }

    private fun name(tool: JsonObject): String? = wire.callName(tool)

    /** The tool as the Messages API takes it: the declaration's name, description and input as its schema. */
    private fun offered(tool: CapabilityTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("input_schema", tool.input)
    }
}
