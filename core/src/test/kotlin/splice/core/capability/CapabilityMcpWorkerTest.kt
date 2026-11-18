// NEW: a capability's worker can be a tool of an MCP server (docs/specs/harness.md, decision 13). What splice.toml must
// say for one to boot, and how `splice status` names it.
package splice.core.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilityMcpWorkerTest {
    private fun table(name: String, worker: String) = """
[capabilities.$name]
heads = ["astra"]
place = "first-user-message"
may = "add"
message = "Checking the request"
color = "cyan"
timeout_s = 30
on_failure = "open"
send = ["first_user_message"]
worker = $worker
"""

    private fun read(vararg tables: String): CapabilityRead =
        CapabilityDeclarations(setOf("astra")).read(CapabilityToml.split(tables.joinToString("")).tables)

    @Test
    fun `a whole MCP worker boots, and status names its server and tool`() {
        val read = read(table("brief", """{ kind = "mcp", server = "notes", tool = "brief" }"""))

        assertEquals(emptyList<Any>(), read.findings)
        assertEquals(CapabilityWorker.Mcp("notes", "brief"), read.capabilities.single().work.worker)
        assertEquals("mcp notes/brief", read.capabilities.single().work.worker.describe())
    }

    @Test
    fun `an MCP worker with no server, or no tool, is a finding naming the key, and a whole one beside it is not`() {
        val read = read(
            table("well-made", """{ kind = "mcp", server = "notes", tool = "brief" }"""),
            table("half-a", """{ kind = "mcp", tool = "brief" }"""),
            table("half-b", """{ kind = "mcp", server = "notes" }"""),
            table("blank", """{ kind = "mcp", server = "", tool = "brief" }"""),
        )

        val paths = read.findings.map { it.path }
        assertEquals(
            listOf(
                "capabilities.half-a.worker.server",
                "capabilities.half-b.worker.tool",
                "capabilities.blank.worker.server",
            ),
            paths,
        )
        assertEquals(listOf("well-made"), read.capabilities.map { it.name })
    }

    @Test
    fun `an MCP worker with a key it does not have is a finding naming it`() {
        val read = read(table("odd", """{ kind = "mcp", server = "notes", tool = "brief", url = "http://x" }"""))

        assertTrue(read.findings.any { it.path == "capabilities.odd.worker.url" })
        assertEquals(emptyList<Capability>(), read.capabilities)
    }
}
