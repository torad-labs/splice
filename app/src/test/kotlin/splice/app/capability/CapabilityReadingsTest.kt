// What the optional programs splice works beside become: the doctor's tools rows (never a failure, and silent about a
// tool the machine does not use) and the "action needed" list an interactive upgrade and `splice setup --update` say.
package splice.app.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.desktop.setup.DesktopMode
import splice.client.ManagedFile
import splice.client.ManagedReading
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

    private val managedFile: Path = Path.of("/etc/claude-code/managed-settings.json")
    private val launch = "/s/desktop-launch"
    private val dropIn = "/etc/claude-code/managed-settings.d/10-org.json"
    private val unknown = "what Desktop does depends on places splice did not read"

    private fun app(state: ManagedFile, unread: List<String> = emptyList(), mode: DesktopMode = DesktopMode.APP) =
        bare().copy(
            desktop = desktop(mode = mode)
                .copy(managed = WrapperSettings(ManagedReading(managedFile, state, unread), Path.of(launch))),
        )

    private fun read(text: String) = ManagedFile.Read(Json.parseToJsonElement(text).jsonObject)

    private fun row(facts: Capabilities) =
        CapabilityTools(facts).readings().singleOrNull { it.name == "managed settings" }

    private fun item(facts: Capabilities) =
        CapabilityGaps(facts, false).afterUpgrade().singleOrNull { it.kind == GapKind.MANAGED_SETTINGS }

    @Test
    fun `a file that lacks parentSettingsBehavior warns, says the effect plainly, and is an item to edit in place`() {
        for (wrapper in listOf("", """"processWrapper": "$launch"""", """"processWrapper": "/usr/bin/other"""")) {
            val facts = app(read("{$wrapper}"))
            val row = row(facts)!!
            assertEquals(ToolLevel.WARN, row.level)
            assertTrue("$managedFile" in row.said && "does not set parentSettingsBehavior" in row.said, row.said)
            assertTrue("Desktop warns in every Code session, and its limits for them are not applied" in row.said)
            assertTrue(row.fix.orEmpty().startsWith("edit it in place as an administrator so it holds both keys"))
            val item = item(facts)!!.text
            assertTrue(row.said in item && "(a root-owned file)" in item, item)
        }
    }

    @Test
    fun `another program's wrapper beside merge is named, warned, and is one item, which names the program`() {
        val facts = app(read("""{"processWrapper": "/usr/bin/other", "parentSettingsBehavior": "merge"}"""))
        assertEquals(ToolLevel.WARN, row(facts)!!.level)
        val item = item(facts)!!.text
        assertTrue("sets processWrapper to \"/usr/bin/other\"" in item, item)
        assertTrue("the Code tab starts through /usr/bin/other, and Desktop passes it no key for splice" in item, item)
    }

    @Test
    fun `first-wins in the file is named as it is, warned, and the item asks for both keys, never for an addition`() {
        val facts = app(read("""{"processWrapper": "$launch", "parentSettingsBehavior": "first-wins"}"""))
        val row = row(facts)!!
        assertEquals(ToolLevel.WARN, row.level)
        assertTrue("sets parentSettingsBehavior to \"first-wins\"" in row.said, row.said)
        assertTrue("add " !in row.fix.orEmpty(), row.fix)
        assertTrue("a copy put in its place would lose its owner, its mode and its other keys" in item(facts)!!.text)
    }

    @Test
    fun `both keys right is fine and no item, and merge without a wrapper is a note and no item`() {
        val ok = app(read("""{"processWrapper": "$launch", "parentSettingsBehavior": "merge"}"""))
        assertEquals(ToolLevel.OK, row(ok)!!.level)
        assertEquals(null, row(ok)!!.fix)
        assertTrue("Desktop does not warn in them" in row(ok)!!.said, row(ok)!!.said)
        assertEquals(null, item(ok))
        val noWrapper = app(read("""{"parentSettingsBehavior": "merge"}"""))
        assertEquals(ToolLevel.INFO, row(noWrapper)!!.level)
        assertTrue("no processWrapper is named" in row(noWrapper)!!.said, row(noWrapper)!!.said)
        assertEquals(null, item(noWrapper))
    }

    @Test
    fun `nothing at the path is said as nothing there, plainly, with how to start the Code tab through splice`() {
        val facts = app(ManagedFile.Absent)
        val row = row(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("nothing is at $managedFile" in row.said, row.said)
        assertTrue("Claude Code reads no managed settings here, so Desktop does not warn" in row.said, row.said)
        assertTrue(row.fix.orEmpty().startsWith("to start the Code tab through splice"), row.fix)
        assertEquals(null, item(facts))
    }

    @Test
    fun `a file splice could not read is said so for both keys, with no effect, no missing key and no item`() {
        val facts = app(ManagedFile.Unreadable)
        val row = row(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("$managedFile could not be read" in row.said, row.said)
        assertTrue("splice does not say whether it exists or what it holds" in row.said, row.said)
        assertTrue(unknown in row.said, row.said)
        for (untrue in listOf("does not set", "nothing is at", "Desktop warns in every Code session, and")) {
            assertTrue(untrue !in row.said, "${row.said} says \"$untrue\" of a file nobody read")
        }
        assertTrue(row.fix.orEmpty().startsWith("look at it as an administrator"), row.fix)
        assertEquals(null, item(facts))
    }

    // An unread source may hold the value that wins: no surface then says what Desktop does or asks for a step alone.
    @Test
    fun `an unread source is named in every file state, no effect is claimed, and no item is made`() {
        val states = listOf(
            read("""{"processWrapper": "$launch", "parentSettingsBehavior": "first-wins"}"""),
            read("""{"processWrapper": "$launch", "parentSettingsBehavior": "merge"}"""),
            read("""{"processWrapper": "/usr/bin/other"}"""),
            ManagedFile.Unreadable,
            ManagedFile.Absent,
        )
        for (state in states) {
            val facts = app(state, listOf(dropIn))
            val row = row(facts)!!
            assertEquals(ToolLevel.INFO, row.level, "$state")
            assertTrue("Claude Code also reads $dropIn, which splice did not read" in row.said, row.said)
            assertTrue(unknown in row.said, row.said)
            for (effect in listOf("Desktop warns in every Code session, and", "does not warn", "limits apply")) {
                assertTrue(effect !in row.said, "${row.said} claims \"$effect\" while $dropIn is unread")
            }
            val fix = row.fix.orEmpty()
            assertTrue(fix.startsWith("check ") && "places splice did not read" in fix, fix)
            assertEquals(null, item(facts), "$state")
        }
    }

    @Test
    fun `a value that is not text is said to be so for either key, in the row and the item`() {
        val facts = app(read("""{"processWrapper": ["/bin/sh", "-c", "s3cret"], "parentSettingsBehavior": null}"""))
        val item = item(facts)!!.text
        assertTrue("sets processWrapper to a value that is not text" in item, item)
        assertTrue("sets parentSettingsBehavior to a value that is not text" in item, item)
        assertTrue("s3cret" !in item && "does not set" !in item, item)
    }

    @Test
    fun `Desktop not in app mode says nothing about the managed settings`() {
        val off = app(read("{}"), mode = DesktopMode.OFF)
        assertEquals(null, row(off))
        assertEquals(null, item(off))
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
    ) = DesktopFacts(program, mode, cli, app)
}

private val DESKTOP = Path.of("/usr/bin/claude-desktop")
private val AGY = Path.of("/usr/local/bin/agy")
private val CODEX = Path.of("/usr/local/bin/codex")
