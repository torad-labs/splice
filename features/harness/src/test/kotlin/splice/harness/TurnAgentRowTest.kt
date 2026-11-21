// desktop-full (df-turn-headers): a turn's perf row names the Claude Code session, subagent and parent agent that sent
// it, from x-claude-code-session-id, x-claude-code-agent-id and x-claude-code-parent-agent-id, so a subagent's turns
// are told apart from the session's own.
package splice.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.client.PARENT_AGENT_HEADER
import java.nio.file.Path

class TurnAgentRowTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    @Test
    fun `a session's own turn names no agent, and its subagent's turn names itself and its parent`() = runBlocking {
        val head = rig.head(emptyList())

        rig.send(head, Turn("s1", opening("Plan the change")))
        val subagent = Turn("s1", opening("Read the tests"), agent = "agent-7")
        rig.send(head, subagent, mapOf(PARENT_AGENT_HEADER to "agent-1"))

        rig.awaitUntil("both rows are written") { rig.perfRows(head).size >= 2 }
        val (own, sub) = rig.perfRows(head)
        assertEquals("s1", own["session_id"]?.jsonPrimitive?.content)
        assertNull(own["agent_id"], "the session's own turn names no agent")
        assertNull(own["parent_agent_id"])
        assertEquals("s1", sub["session_id"]?.jsonPrimitive?.content)
        assertEquals("agent-7", sub["agent_id"]?.jsonPrimitive?.content)
        assertEquals("agent-1", sub["parent_agent_id"]?.jsonPrimitive?.content)
    }
}
