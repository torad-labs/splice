// NEW: the agent's call (docs/specs/harness.md, decision 12), split out of AgentRounds: where a round's messages go
// once the provider has made them, and where the capability calls made beside the client's tools start.
package splice.harness

import splice.harness.agent.ModelCall

/** Where the provider's messages are kept, and the calls made beside the client's are started. */
internal class AgentKeep(private val records: AgentRecords, private val besides: AgentBeside) {
    fun put(session: String, call: KeptCall) {
        records.put(session, call)
    }

    suspend fun beside(session: String, call: KeptCall, asked: List<ModelCall>, on: CallFor) {
        besides.start(session, call, asked, on)
    }
}
