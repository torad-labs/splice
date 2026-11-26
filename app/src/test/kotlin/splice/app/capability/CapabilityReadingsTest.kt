// What the optional programs splice works beside become: the doctor's tools rows (never a failure, and silent about a
// tool the machine does not use) and the "action needed" list an interactive upgrade and `splice setup --update` say.
package splice.app.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.desktop.setup.DesktopMode
import splice.client.ParentSetting
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

    private val managedFile: Path = Path.of("/etc/claude-code/managed-settings.json")

    private fun appWith(
        parent: ParentSetting?,
        wrapper: WrapperState = WrapperState.NONE,
        elsewhere: List<String> = emptyList(),
        state: ManagedFileState = ManagedFileState.READ,
    ) =
        bare().copy(
            desktop = desktop(mode = DesktopMode.APP, wrapper = wrapper)
                .copy(managedFile = parent?.let { ManagedFileFacts(managedFile, state, it, elsewhere) }),
        )

    private fun parentRow(facts: Capabilities) =
        CapabilityTools(facts).readings().singleOrNull { it.name == "parentSettingsBehavior" }

    private fun parentGap(facts: Capabilities) =
        CapabilityGaps(facts, false).afterUpgrade().singleOrNull { it.kind == GapKind.PARENT_SETTINGS }

    @Test
    fun `a managed file without parentSettingsBehavior warns and is an action item, with a wrapper or without`() {
        for (wrapper in listOf(WrapperState.NONE, WrapperState.SPLICE, WrapperState.OTHER)) {
            val facts = appWith(ParentSetting(), wrapper)
            val row = parentRow(facts)!!
            assertEquals(ToolLevel.WARN, row.level)
            assertTrue("$managedFile does not set parentSettingsBehavior" in row.said, row.said)
            assertTrue("Desktop warns in every one" in row.said, row.said)
            assertEquals(true, row.fix?.contains("add \"parentSettingsBehavior\": \"merge\""), row.fix)
            val gap = parentGap(facts)!!.text
            assertTrue("does not set parentSettingsBehavior" in gap && "add \"parentSettingsBehavior\"" in gap, gap)
        }
    }

    @Test
    fun `a managed file that sets first-wins is said as the administrator's choice, not warned or asked for`() {
        val facts = appWith(ParentSetting("\"first-wins\""))
        val row = parentRow(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("sets parentSettingsBehavior to \"first-wins\"" in row.said, row.said)
        assertTrue("administrator's choice" in row.fix.orEmpty(), row.fix)
        assertTrue("add" !in row.fix.orEmpty(), "a key already there is changed, never added twice: ${row.fix}")
        val gap = parentGap(facts)!!.text
        assertTrue("administrator's choice" in gap && "to \"first-wins\"" in gap, gap)
    }

    @Test
    fun `a managed file that says merge is fine, and no file at all says nothing`() {
        val ok = appWith(ParentSetting("\"merge\""), WrapperState.SPLICE)
        assertEquals(ToolLevel.OK, parentRow(ok)!!.level)
        assertEquals(null, parentGap(ok))
        val none = appWith(null, WrapperState.SPLICE)
        assertEquals(null, parentRow(none))
        assertEquals(null, parentGap(none))
    }

    @Test
    fun `Desktop not in app mode says nothing about parentSettingsBehavior`() {
        val off = appWith(ParentSetting()).copy(desktop = appWith(ParentSetting()).desktop.copy(mode = DesktopMode.OFF))
        assertEquals(null, parentRow(off))
        assertEquals(null, parentGap(off))
    }

    @Test
    fun `where another source was not read it is named as unread and the key is not called missing`() {
        val facts = appWith(ParentSetting(), elsewhere = listOf("/etc/claude-code/managed-settings.d/10-org.json"))
        val said = parentRow(facts)!!.said
        assertTrue("did not read /etc/claude-code/managed-settings.d/10-org.json" in said, said)
        assertTrue("may win over the file's" in said, said)
        assertTrue("; if no source sets \"merge\", Claude Code drops the policy" in said, said)
        assertEquals(ToolLevel.INFO, parentRow(facts)!!.level, "what Desktop does is not known: noted, not warned")
        assertTrue("check whether one of those sets it; if none does, add" in parentRow(facts)!!.fix.orEmpty())
        assertEquals(null, parentGap(facts), "the key may already be in force, so no item asks for it")
    }

    @Test
    fun `a merge in the main file with another source unread is not said to be in force`() {
        val dropIn = "/etc/claude-code/managed-settings.d/a.json"
        val facts = appWith(ParentSetting("\"merge\""), elsewhere = listOf(dropIn))
        val row = parentRow(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("sets parentSettingsBehavior to \"merge\"; splice did not read" in row.said, row.said)
        assertTrue("limits apply" !in row.said, row.said)
        assertEquals(null, parentGap(facts))
    }

    @Test
    fun `a value that is not text is said to be so and never printed, in the row and the item`() {
        val facts = appWith(ParentSetting(notText = true))
        val row = parentRow(facts)!!
        assertTrue("sets parentSettingsBehavior to a value that is not text" in row.said, row.said)
        val gap = parentGap(facts)!!.text
        assertTrue("sets parentSettingsBehavior to a value that is not text" in gap, gap)
    }

    @Test
    fun `a file splice could not read is said to be unread for both keys, as a note and never as an item`() {
        val facts = appWith(ParentSetting(), WrapperState.UNREADABLE, state = ManagedFileState.UNREADABLE)
        val parent = parentRow(facts)!!
        val wrapper = CapabilityTools(facts).readings().single { it.name == "processWrapper" }
        for ((row, key) in listOf(parent to "parentSettingsBehavior", wrapper to "processWrapper")) {
            assertEquals(ToolLevel.INFO, row.level, row.said)
            assertTrue("$managedFile could not be read" in row.said, row.said)
            assertTrue("splice cannot say what it holds for $key" in row.said, row.said)
            for (untrue in listOf("does not set", "names no", "sets parentSettingsBehavior", "warns in every")) {
                assertTrue(untrue !in row.said, "${row.said} says \"$untrue\" of a file nobody read")
            }
            assertTrue("read it as an administrator" in row.fix.orEmpty() && "add" !in row.fix.orEmpty(), row.fix)
        }
        assertEquals(null, parentGap(facts), "nothing is known to be missing from it, so there is nothing to do")
        assertTrue(CapabilityGaps(facts, false).afterUpgrade().none { it.kind == GapKind.PROCESS_WRAPPER })
    }

    @Test
    fun `no main file with another source present is named, as a note, with no item`() {
        val dropIn = "/etc/claude-code/managed-settings.d/10-org.json"
        val facts = appWith(ParentSetting(), elsewhere = listOf(dropIn), state = ManagedFileState.MISSING)
        val row = parentRow(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("there is no $managedFile" in row.said, row.said)
        assertTrue("splice did not read $dropIn" in row.said, row.said)
        assertTrue("check that one of them sets parentSettingsBehavior to \"merge\"" in row.fix.orEmpty(), row.fix)
        assertEquals(null, parentGap(facts))
    }

    @Test
    fun `the processWrapper row and gap for another program's wrapper name the program and say nothing of the key`() {
        val facts = appWith(ParentSetting("\"merge\""), WrapperState.OTHER)
        val row = CapabilityTools(facts).readings().single { it.name == "processWrapper" }
        assertEquals(ToolLevel.WARN, row.level)
        val gap = CapabilityGaps(facts, false).afterUpgrade().single { it.kind == GapKind.PROCESS_WRAPPER }.text
        assertTrue("another program" in gap && "parentSettingsBehavior" !in gap, gap)
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
