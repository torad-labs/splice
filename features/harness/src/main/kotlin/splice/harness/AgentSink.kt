// NEW: the agent's call (docs/specs/harness.md, decision 12). One round's stream on its way to the client: every frame
// is put together into the model's message, and a call of a capability's tool never reaches the client. Its block is
// opened under an index of its own that no client write names, so the client's blocks keep their numbers in order.
package splice.harness

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.upstream.sse.SourceFrameAction
import splice.upstream.sse.WireSink

/** [client] with the calls of [tools] taken out, and the round's message put together in [message]. [hidden] is the
 *  indices the capability's calls were opened under, shared by every writer a frame of the round is delivered
 *  through. */
internal class AgentSink(
    private val client: WireSink,
    private val tools: Set<String>,
    val message: MessageAssembly = MessageAssembly(tools),
    private val hidden: MutableSet<Int> = mutableSetOf(),
) : WireSink by client {
    /** The same round's sink over [wire], the writer a source frame is delivered through. */
    private fun over(wire: WireSink): WireSink = if (wire === client) this else AgentSink(wire, tools, message, hidden)

    private fun shown(index: WireBlockIndex): Boolean = index.value !in hidden

    override suspend fun withSourceFrame(event: JsonObject, action: SourceFrameAction) {
        message.accept(event)
        client.withSourceFrame(event) { wire -> action.deliver(over(wire)) }
    }

    override suspend fun openTool(id: String, name: String): WireBlockIndex {
        if (name !in tools) return client.openTool(id, name)
        val index = HIDDEN_FIRST - hidden.size
        hidden += index
        return WireBlockIndex(index)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        if (shown(index)) client.inputJsonDelta(index, partialJson)
    }

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        if (shown(index)) client.textDelta(index, text)
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        if (shown(index)) client.thinkingDelta(index, thinking)
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        if (shown(index)) client.signatureDelta(index, signature)
    }

    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        if (shown(index)) client.rawDelta(index, delta)
    }

    override suspend fun closeBlock(index: WireBlockIndex) {
        if (shown(index)) client.closeBlock(index)
    }

    override suspend fun relayEvent(event: JsonObject, index: WireBlockIndex?) {
        if (index == null || shown(index)) client.relayEvent(event, index)
    }
}

/** why: client blocks are numbered up from 0, so the hidden ones count down from -1 and no client write names one. */
private const val HIDDEN_FIRST = -1
