// NEW: the door modes as the harness's agent-tool port, at one head (modes/README.md; docs/specs/harness.md, decision
// 12, Modes): each mode on for the session's head and caller offers its tools, named mcp__<server>__<tool>, and its
// prompt, and a call of one of its tools is answered by the mode's handler, with the session and the mode's store.
package splice.app.modes

import kotlinx.serialization.json.JsonObject
import splice.harness.agent.AgentOffer
import splice.harness.agent.AgentOfferedTool
import splice.harness.agent.AgentSession
import splice.harness.agent.AgentToolAnswer
import splice.harness.agent.AgentToolPort
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeCaller
import splice.modes.api.ModeKind
import splice.modes.api.ModeSession

/** Head [head]'s door modes, read from [modes] on every request, so a switch reaches the next one. */
internal class ModeTools(private val modes: DaemonModes, private val head: String) : AgentToolPort {
    override fun offer(session: AgentSession): AgentOffer {
        val caller = ModeCaller.entries.firstOrNull { it.wire == session.caller } ?: return AgentOffer()
        val on = modes.offered(head, caller)
        val tools = on.flatMap { mode ->
            mode.tools.map { AgentOfferedTool(it.offeredAs, it.description, it.schema, "${mode.name}: ${it.name}") }
        }
        return AgentOffer(tools, on.mapNotNull { it.prompt })
    }

    override fun answer(tool: String, input: JsonObject, session: AgentSession): AgentToolAnswer {
        val mode = modes.catalog.modes.firstOrNull { found ->
            found.manifest.kind == ModeKind.DOOR && found.tools.any { it.offeredAs == tool }
        } ?: return AgentToolAnswer("splice has no mode tool $tool", error = true)
        val name = mode.tools.first { it.offeredAs == tool }.name
        val caller = ModeCaller.entries.firstOrNull { it.wire == session.caller } ?: ModeCaller.HEAD
        val call = ModeCall(
            name,
            input,
            ModeSession(session.id, session.folder, session.head, session.model, caller),
            modes.store(mode.name),
        )
        return when (val run = ModeFailure.run(mode.name) { mode.handler.answer(call) }) {
            is ModeRun.Threw -> AgentToolAnswer(run.said, error = true)
            is ModeRun.Done -> when (val answer = run.value) {
                is ModeAnswer.Text -> AgentToolAnswer(answer.text, error = false)
                is ModeAnswer.Error -> AgentToolAnswer(answer.text, error = true)
            }
        }
    }
}
