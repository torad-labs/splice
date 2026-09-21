// NEW: V4-176 — the slice hosted MCP servers land in is a NAME THE OPERATOR SUPPLIES, not a
// constant splice asserts about the box.
//
// The requirement is real and splice cannot satisfy it: a hosted server that inherits splice's own
// cgroup is uncapped, and a cap is a policy about the whole machine. What was wrong was stating it
// as a fact — one hardcoded slice name, plus prose naming the private tool that declares it on the
// operator's own machine. A reader packaging splice for their box could act on none of that.
//
// These arms pin the two halves of the contract that can actually regress: the configured name
// reaches the spawn, and a slice with no ceiling is still reported as NO containment — because the
// second is what stops the first from becoming a way to claim containment by naming something.
package splice.control.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.LogSink

private val COMMAND = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem")

class ConfiguredSliceTest {

    private val log = StringBuilder()
    private val sink = LogSink { synchronized(log) { log.append(it) } }

    // Mutant: keep reading the constant inside McpContainment. The operator sets the knob, the
    // console shows it set, and every child still lands in a slice nobody declared — a configured
    // value that changes nothing is worse than no knob, because it reads as done.
    @Test
    fun `the configured slice is the one the child is spawned into`() {
        val placed = McpContainment(sink, slice = "my-mcp.slice", cap = { "8589934592" }).placed(COMMAND)

        assertEquals(
            listOf("systemd-run", "--user", "--scope", "--quiet", "--slice=my-mcp.slice", "--") + COMMAND,
            placed,
        )
    }

    // Mutant: treat a NAMED slice as a capped one. Naming a slice is not declaring a ceiling on it —
    // systemd answers LoadState=loaded for a slice nobody declared — so a configured name must not
    // buy the containment claim that the ceiling buys.
    @Test
    fun `naming a slice does not make it containment`() {
        val placed = McpContainment(sink, slice = "my-mcp.slice", cap = { "infinity" }).placed(COMMAND)

        assertEquals(COMMAND, placed, "an uncapped slice must leave the command unplaced")
        assertTrue(log.toString().contains("my-mcp.slice declares no memory ceiling"), log.toString())
    }

    // The default is what this repo's own layout produces, so a box that changes nothing needs no
    // config at all — and the host config is where the name travels, beside the other lifecycle
    // values the daemon reads from the knob layer.
    @Test
    fun `the host config defaults to the declared slice`() {
        assertEquals(APP_MCP_SLICE, McpHostConfig().slice)
        assertEquals("app-mcp.slice", APP_MCP_SLICE, "the default is the systemd dash-nesting name")
    }
}
