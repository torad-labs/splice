// NEW: a tool the model may call that splice answers (docs/specs/harness.md, decision 12): a capability's, one tool a
// capability, or one a source beside the capabilities offers through the agent-tool port (a mode's, many tools a
// mode). The agent's call offers, answers and keeps both through the same path.
package splice.harness.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.capability.Capability

internal sealed class AgentTool {
    /** The name a kept call records it by: the capability's, or the tool's offered name. */
    abstract val name: String

    /** What a turn held on it is recorded and logged as: the capability's name, or the port tool's label. */
    abstract val label: String

    class OfCapability(val capability: Capability) : AgentTool() {
        override val name: String get() = capability.name
        override val label: String get() = capability.name
    }

    class OfPort(val offered: AgentOfferedTool, val port: AgentToolPort, val session: AgentSession) : AgentTool() {
        override val name: String get() = offered.name
        override val label: String get() = offered.label

        /** The tool as the Messages API takes it. */
        val tool: JsonObject = buildJsonObject {
            put("name", offered.name)
            put("description", offered.description)
            put("input_schema", offered.schema)
        }
    }
}
