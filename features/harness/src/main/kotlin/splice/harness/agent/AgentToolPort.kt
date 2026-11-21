// NEW: tools the model may call that splice answers, from a source beside the capabilities (docs/specs/harness.md,
// decision 12): a mode's tools (modes/README.md). The harness offers them after the client's tools, places their
// guidance at the end of the system text, and answers their calls through the same path as a capability's, many tools a
// source. :app adapts the modes onto this port per head; the harness knows nothing of modes.
package splice.harness.agent

import kotlinx.serialization.json.JsonObject

/** What a session's requests are offered, and the answer to a call of one of the offered tools. */
public interface AgentToolPort {
    /** What a request of [session] is offered: tools after the client's, in order, and the texts placed once at the end
     *  of its system text, in order. Empty when none is on for the session. */
    public fun offer(session: AgentSession): AgentOffer

    /** The answer to a call of the offered tool [tool] with [input], for [session]. */
    public fun answer(tool: String, input: JsonObject, session: AgentSession): AgentToolAnswer
}

/** The session a request is of: its id, its folder when known, its head, its model, and who sends it (`door`, `head`
 *  or `desktop`). */
public data class AgentSession(
    val id: String,
    val folder: String?,
    val head: String,
    val model: String,
    val caller: String,
)

/** The tools and the system texts one request is offered. */
public data class AgentOffer(val tools: List<AgentOfferedTool> = emptyList(), val prompts: List<String> = emptyList())

/** One offered tool: its [name] as the model sees it, its description and input schema, and the [label] a turn held on
 *  it says on the status line. */
public data class AgentOfferedTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    val label: String,
)

/** A call's answer: the text the model reads as the call's result, an error result when [error]. */
public data class AgentToolAnswer(val text: String, val error: Boolean)

/** A head with no tools beside its capabilities. */
public object NoAgentTools : AgentToolPort {
    override fun offer(session: AgentSession): AgentOffer = AgentOffer()

    override fun answer(tool: String, input: JsonObject, session: AgentSession): AgentToolAnswer =
        AgentToolAnswer("there is no tool $tool on this head", error = true)
}
