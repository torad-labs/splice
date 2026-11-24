// What the optional programs splice works beside become: the doctor's tools rows (never a failure, and silent about a
// tool the machine does not use) and the "action needed" list an interactive upgrade and `splice setup --update` say.
package splice.app.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.desktop.setup.DesktopMode
import splice.core.mcp.McpHarness
import splice.diagnostics.doctor.ToolLevel
import java.nio.file.Path

class CapabilityReadingsTest {

    @Test
    fun `a machine with none of the tools has no rows and no gaps beyond the auto-update choice`() {
        assertEquals(emptyList<Any>(), CapabilityTools(bare()).readings())
        assertEquals(listOf(GapKind.AUTO_UPDATE), CapabilityGaps(bare(), claudeWrapped = false).all().map { it.kind })
        assertEquals(emptyList<Gap>(), CapabilityGaps(bare(), claudeWrapped = false).afterUpgrade())
    }

    @Test
    fun `a Desktop setup without Claude Desktop warns, and never fails`() {
        val facts = bare().copy(desktop = desktop(program = null, mode = DesktopMode.APP))
        val row = CapabilityTools(facts).readings().single { it.name == "claude-desktop" }
        assertEquals(ToolLevel.WARN, row.level)
        assertTrue("not installed" in row.said, row.said)
    }

    // `claude-splice` runs Claude Code in a terminal: it is no use of Claude Desktop, so a machine with only that
    // launcher and no Desktop has nothing to say about Desktop.
    @Test
    fun `the claude-splice launcher alone says nothing about Claude Desktop`() {
        val facts = bare().copy(desktop = desktop(program = null, cli = true))
        assertTrue(CapabilityTools(facts).readings().none { it.name == "claude-desktop" })
    }

    @Test
    fun `app mode with a managed processWrapper naming another program warns and is an action item`() {
        val facts = bare().copy(desktop = desktop(mode = DesktopMode.APP, wrapper = WrapperState.OTHER))
        val row = CapabilityTools(facts).readings().single { it.name == "processWrapper" }
        assertEquals(ToolLevel.WARN, row.level)
        assertTrue(GapKind.PROCESS_WRAPPER in CapabilityGaps(facts, false).afterUpgrade().map { it.kind })
    }

    @Test
    fun `splice's own processWrapper, or none, is no action item`() {
        for (wrapper in listOf(WrapperState.SPLICE, WrapperState.NONE)) {
            val facts = bare().copy(desktop = desktop(mode = DesktopMode.APP, wrapper = wrapper))
            assertTrue(CapabilityGaps(facts, false).afterUpgrade().none { it.kind == GapKind.PROCESS_WRAPPER })
            assertTrue(CapabilityTools(facts).readings().none { it.level == ToolLevel.WARN })
        }
    }

    @Test
    fun `Desktop on the older Code-tab door is an action item that names splice desktop on`() {
        val gaps = CapabilityGaps(bare().copy(desktop = desktop(mode = DesktopMode.STANDARD)), false).afterUpgrade()
        assertTrue(gaps.any { it.kind == GapKind.DESKTOP_STANDARD && "splice desktop on" in it.text }, gaps.toString())
    }

    @Test
    fun `agy and codex without Splice MCP are action items, and a missing shim is a warning`() {
        val facts = bare().copy(harness = HarnessFacts(AGY, CODEX, geminiShim = false, synced = emptySet()))
        val gaps = CapabilityGaps(facts, false).afterUpgrade().map { it.kind }
        assertEquals(2, gaps.count { it == GapKind.MCP_SYNC })
        assertTrue(GapKind.GEMINI_SHIM in gaps)
        assertEquals(ToolLevel.WARN, CapabilityTools(facts).readings().single { it.name == "antigravity shim" }.level)
    }

    @Test
    fun `synced harnesses and a laid shim say nothing to do`() {
        val synced = setOf(McpHarness.AGY, McpHarness.CODEX)
        val facts = bare().copy(harness = HarnessFacts(AGY, CODEX, geminiShim = true, synced = synced))
        assertEquals(emptyList<Gap>(), CapabilityGaps(facts, false).afterUpgrade())
        assertTrue(CapabilityTools(facts).readings().all { it.level == ToolLevel.OK })
    }

    @Test
    fun `a wrapped claude, or an installed Claude Desktop off splice, is offered the side-by-side launchers`() {
        assertTrue(CapabilityGaps(bare(), claudeWrapped = true).afterUpgrade().any { it.kind == GapKind.SIDE_BY_SIDE })
        val found = bare().copy(desktop = desktop())
        assertTrue(CapabilityGaps(found, false).afterUpgrade().any { it.kind == GapKind.SIDE_BY_SIDE })
        val launched = bare().copy(desktop = desktop(app = true))
        assertTrue(CapabilityGaps(launched, false).afterUpgrade().none { it.kind == GapKind.SIDE_BY_SIDE })
    }

    @Test
    fun `the MCP hub without systemd warns, with systemd it is fine, and with the hub off it says nothing`() {
        val missing = bare().copy(hub = HubFacts(linux = true, on = true, systemd = false))
        assertEquals(ToolLevel.WARN, CapabilityTools(missing).readings().single { it.name == "systemd" }.level)
        assertTrue(CapabilityGaps(missing, false).afterUpgrade().any { it.kind == GapKind.HUB_SYSTEMD })
        val fine = bare().copy(hub = HubFacts(linux = true, on = true, systemd = true))
        assertEquals(ToolLevel.OK, CapabilityTools(fine).readings().single { it.name == "systemd" }.level)
        val off = bare().copy(hub = HubFacts(linux = true, on = false, systemd = false))
        assertTrue(CapabilityTools(off).readings().none { it.name == "systemd" })
    }

    private fun bare() = Capabilities(
        desktop = desktop(program = null),
        harness = HarnessFacts(agy = null, codex = null, geminiShim = false, synced = emptySet()),
        hub = HubFacts(linux = true, on = false, systemd = false),
        autoUpdate = null,
    )

    private fun desktop(
        program: Path? = DESKTOP,
        mode: DesktopMode = DesktopMode.OFF,
        cli: Boolean = false,
        app: Boolean = false,
        wrapper: WrapperState = WrapperState.NONE,
    ) = DesktopFacts(program, mode, cli, app, wrapper)
}

private val DESKTOP = Path.of("/usr/bin/claude-desktop")
private val AGY = Path.of("/usr/local/bin/agy")
private val CODEX = Path.of("/usr/local/bin/codex")
