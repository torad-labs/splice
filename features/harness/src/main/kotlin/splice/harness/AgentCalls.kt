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
import splice.harness.agent.AgentAsk
import splice.harness.agent.AgentSystem
import splice.harness.agent.AgentTool
import splice.harness.agent.AgentToolPort
import splice.harness.agent.AgentWire
import splice.head.port.HarnessRequest
import splice.head.port.HarnessShape
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
    /** The tools beside the capabilities (a mode's), offered and answered through the same path. */
    private val port: AgentToolPort,
    private val records: AgentRecords,
    private val answers: AgentAnswers,
    private val besides: AgentBeside,
    private val stops: AgentStops,
) {
    private val rules = CapabilityRules()
    private val wire = AgentWire()
    private val system = AgentSystem()
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
        val on = CallFor(head, turn, request)
        val ported = ported(request, on)
        val idle = agents.isEmpty() && ported.tools.isEmpty()
        if (session == null || idle) return AgentEnd.Go()
        val sent = turn.sending
        val kept = when (val owed = besides.paid(session, history.matches(sent, records.of(session)), on)) {
            is Owed.Refused -> return AgentEnd.Refused(owed.why)
            is Owed.Paid -> owed.matches
        }
        val clients = wire.toolNames(sent)
        val offering = agents.filter { (capability, tool) ->
            rules.offers(capability, head, request) && name(tool) !in clients
        }
        val ports = ported.tools.filter { it.name !in clients }
        val carried = (offering.map { it.second } + ports.map { it.tool } + kept.flatMap { it.call.tools })
            .distinctBy(::name)
        val body = system.placed(wire.withTools(history.restored(sent, kept), carried), ported.prompts)
        val names = carried.mapNotNull(::name).toSet()
        val answering = agents.mapNotNull { (capability, tool) ->
            name(tool)?.takeIf { it in names }?.let { it to AgentTool.OfCapability(capability) }
        }.toMap() + ports.filter { it.name in names }.associateBy { it.name }
        val rounds = answering.takeIf { it.isNotEmpty() }?.let {
            AgentRounds(session, it, carried, on, answers, keep, AgentAsk(body, turn))
        }
        return AgentEnd.Go(body.takeUnless { it === sent }, rounds)
    }

    /** The port's tools and prompts for [request]: only a turn that carries tools is offered them, and never the
     *  requests decision 12 rules out (a worker's own session, the auto-mode review). */
    private fun ported(request: HarnessRequest, on: CallFor): Ported {
        val session = on.agentSession()?.takeIf { offered(request.shape) } ?: return Ported()
        val offer = port.offer(session)
        return Ported(offer.tools.map { AgentTool.OfPort(it, port, session) }, offer.prompts)
    }

    /** Whether a request of [shape] is offered the port's tools: one that carries tools, and neither a worker's own
     *  session nor the auto-mode review. */
    private fun offered(shape: HarnessShape): Boolean = shape.tools && !shape.review && shape.workedFor == null

    /** What the port offers one request. */
    private data class Ported(val tools: List<AgentTool.OfPort> = emptyList(), val prompts: List<String> = emptyList())

    private fun name(tool: JsonObject): String? = wire.callName(tool)

    /** The tool as the Messages API takes it: the declaration's name, description and input as its schema. */
    private fun offered(tool: CapabilityTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("input_schema", tool.input)
    }
}
