// What the optional programs splice works beside become: the doctor's tools rows (never a failure, and silent about a
// tool the machine does not use) and the "action needed" list an interactive upgrade and `splice setup --update` say.
package splice.app.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.setup.DesktopMode
import splice.client.ManagedFile
import splice.client.ManagedReading
import splice.core.mcp.McpHarness
import splice.diagnostics.doctor.ToolLevel
import splice.lifecycle.seatd.SeatdInstaller
import java.nio.file.Files
import java.nio.file.Path

class CapabilityReadingsTest(@param:TempDir private val tmp: Path) {

    @Test
    fun `a machine without Desktop can skip seatd and still sees how to add it after upgrading`() {
        val row = CapabilityTools(bare()).readings().single()
        assertEquals("seatd-pipe", row.name)
        assertEquals(ToolLevel.INFO, row.level)
        val item = CapabilityGaps(bare(), claudeWrapped = false).afterUpgrade().single().text
        assertTrue("splice setup --update" in item && "splice desktop on" in item, item)
    }

    @Test
    fun `Desktop on without the companion is a warning with a fix`() {
        val facts = bare().copy(desktop = desktop(mode = DesktopMode.APP))
        val row = CapabilityTools(facts).readings().single { it.name == "seatd-pipe" }
        assertEquals(ToolLevel.WARN, row.level)
        assertTrue("splice setup --update" in row.fix.orEmpty(), row.fix)
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
    private val launch: Path = tmp.resolve("desktop-launch")
    private val dropIn = "/etc/claude-code/managed-settings.d/10-org.json"
    private val wanted = "wanted: processWrapper \"$launch\" and parentSettingsBehavior \"merge\""
    private val condition = "wherever managed settings exist, Desktop warns in every Code session unless the source " +
        "that wins sets parentSettingsBehavior to \"merge\""
    private val effects = listOf("Desktop does not warn", "limits apply", "its limits for them are not applied")

    init {
        Files.writeString(launch, "#!/bin/sh\n")
        launch.toFile().setExecutable(true)
    }

    private fun app(
        state: ManagedFile,
        unread: List<String> = emptyList(),
        mode: DesktopMode = DesktopMode.APP,
        dropIns: List<Pair<Path, JsonObject>> = emptyList(),
    ): Capabilities {
        val read = listOfNotNull((state as? ManagedFile.Read)?.let { managedFile to it.settings }) + dropIns
        val reading = ManagedReading(managedFile, state, read, unread)
        return bare().copy(desktop = desktop(mode = mode).copy(managed = WrapperSettings(reading, launch)))
    }

    private fun dropIn(text: String) = Path.of(dropIn) to Json.parseToJsonElement(text).jsonObject

    private fun read(text: String) = ManagedFile.Read(Json.parseToJsonElement(text).jsonObject)

    private fun right() = read("""{"processWrapper": "$launch", "parentSettingsBehavior": "merge"}""")

    private fun row(facts: Capabilities) =
        CapabilityTools(facts).readings().singleOrNull { it.name == "managed settings" }

    /** The upgrade's checklist lines that speak of the managed settings, as a person reads them. */
    private fun items(facts: Capabilities) =
        CapabilityGaps(facts, false).afterUpgrade().map { it.text }.filter { "$managedFile" in it }

    private val states = listOf(
        read("""{"processWrapper": "$launch", "parentSettingsBehavior": "first-wins"}"""),
        read("""{"processWrapper": "/usr/bin/other"}"""),
        ManagedFile.Unreadable,
        ManagedFile.Absent,
    )

    @Test
    fun `every surface says what is wanted, then the condition once, and never what Desktop does as a fact`() {
        val facts = listOf(emptyList(), listOf(dropIn)).flatMap { unread -> (states + right()).map { app(it, unread) } }
        for (text in facts.flatMap { listOf(row(it)!!.said) + items(it) }) {
            assertTrue(text.startsWith(wanted), text)
            assertEquals(1, text.split(condition).size - 1, text)
            effects.forEach { assertTrue(it !in text, "\"$it\" said as a fact: $text") }
        }
    }

    @Test
    fun `a file that lacks parentSettingsBehavior warns and is an item to edit in place`() {
        for (wrapper in listOf("", """"processWrapper": "$launch"""", """"processWrapper": "/usr/bin/other"""")) {
            val facts = app(read("{$wrapper}"))
            val row = row(facts)!!
            assertEquals(ToolLevel.WARN, row.level)
            assertTrue("no managed settings file splice read sets parentSettingsBehavior" in row.said, row.said)
            assertTrue(row.fix.orEmpty().startsWith("make $managedFile hold "), row.fix)
            assertTrue("parentSettingsBehavior \"merge\", editing it in place as an administrator" in row.fix.orEmpty())
            val item = items(facts).single()
            assertTrue(row.said in item && "what to do: make $managedFile hold" in item, item)
        }
    }

    @Test
    fun `another program's wrapper is named, warned and an item, and nothing says what starts the Code tab`() {
        val facts = app(read("""{"processWrapper": "/usr/bin/other", "parentSettingsBehavior": "merge"}"""))
        assertEquals(ToolLevel.WARN, row(facts)!!.level)
        val item = items(facts).single()
        assertTrue("sets processWrapper to \"/usr/bin/other\"" in item && wanted in item, item)
        assertTrue("starts through" !in item, item)
    }

    @Test
    fun `first-wins in the file is named as it is, warned, and the item changes only that key, in that file`() {
        val facts = app(read("""{"processWrapper": "$launch", "parentSettingsBehavior": "first-wins"}"""))
        val row = row(facts)!!
        assertEquals(ToolLevel.WARN, row.level)
        assertTrue("sets parentSettingsBehavior to \"first-wins\"" in row.said, row.said)
        assertTrue(row.fix.orEmpty().startsWith("make $managedFile hold parentSettingsBehavior \"merge\","), row.fix)
        assertTrue("processWrapper \"" !in row.fix.orEmpty(), row.fix)
        assertTrue(row.fix.orEmpty().endsWith("after a change, quit Claude Desktop entirely and reopen it"), row.fix)
        assertTrue(row.fix.orEmpty() in items(facts).single())
    }

    @Test
    fun `both keys right with the launcher laid is a note and no item, whatever other places are named`() {
        for (unread in listOf(emptyList(), listOf(dropIn))) {
            val ok = app(right(), unread)
            assertEquals(ToolLevel.INFO, row(ok)!!.level, "which source wins is never known")
            assertEquals(emptyList<String>(), items(ok), "the file needs no edit, so there is no item")
        }
        assertEquals(null, row(app(right()))!!.fix)
        val check = "check that none of the places splice did not read sets either key to another value"
        assertEquals(check, row(app(right(), listOf(dropIn)))!!.fix, "nothing to edit, so the check alone, no restart")
        val noWrapper = app(read("""{"parentSettingsBehavior": "merge"}"""))
        assertEquals(ToolLevel.INFO, row(noWrapper)!!.level)
        val noFile = "no managed settings file splice read sets processWrapper"
        assertTrue(noFile in items(noWrapper).single(), items(noWrapper).toString())
    }

    @Test
    fun `both keys right with no launcher laid is a warning and an item that says to lay it`() {
        Files.delete(launch)
        val facts = app(right())
        assertEquals(ToolLevel.WARN, row(facts)!!.level)
        assertTrue("$launch is not laid yet: run `splice desktop on` first" in items(facts).single())
    }

    @Test
    fun `both keys right with a directory at the launcher path warns and says to remove or move it first`() {
        Files.delete(launch)
        Files.createDirectories(launch)
        val facts = app(right())
        val expected = "$launch is a directory, not splice's launcher: remove or move it, then run `splice desktop on`"
        assertEquals(ToolLevel.WARN, row(facts)!!.level)
        assertTrue(expected in row(facts)!!.said, row(facts)!!.said)
        assertTrue("is not laid yet" !in row(facts)!!.said, row(facts)!!.said)
        assertTrue(expected in items(facts).single(), items(facts).single())
    }

    @Test
    fun `nothing at the path is said as nothing there, with the step to create it, as an item`() {
        val facts = app(ManagedFile.Absent)
        val row = row(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("nothing is at $managedFile" in row.said, row.said)
        assertTrue(row.fix.orEmpty().startsWith("create $managedFile holding processWrapper"), row.fix)
        assertTrue("create $managedFile holding" in items(facts).single())
    }

    @Test
    fun `a file splice could not read is said so for both keys, with no missing key, as an item`() {
        val facts = app(ManagedFile.Unreadable)
        val row = row(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("$managedFile could not be read" in row.said, row.said)
        assertTrue("splice does not say whether it exists or what it holds" in row.said, row.said)
        for (untrue in listOf("file splice read sets", "nothing is at")) {
            assertTrue(untrue !in row.said, "${row.said} says \"$untrue\" of a file nobody read")
        }
        assertTrue("as an administrator, editing it in place where it exists" in items(facts).single())
    }

    @Test
    fun `a folder splice could not list is said so by folder path, never absent, as an item`() {
        val folder = managedFile.parent
        val facts = app(ManagedFile.Unlistable(folder), unread = listOf("$folder (folder could not be listed)"))
        val row = row(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("$folder could not be listed, so splice could not check its contents or drop-ins" in row.said)
        assertTrue("nothing is at" !in row.said && "file splice read sets" !in row.said, row.said)
        assertTrue("as an administrator, editing it in place where it exists" in items(facts).single())
    }

    @Test
    fun `an unlistable folder that still yielded a readable drop-in does not claim drop-ins were unchecked`() {
        val folder = managedFile.parent
        val facts = app(
            ManagedFile.Unlistable(folder),
            dropIns = listOf(dropIn("""{"parentSettingsBehavior": "merge"}""")),
        )
        val said = row(facts)!!.said
        assertTrue("$folder could not be listed, so splice could not check all of its contents" in said, said)
        assertTrue("or drop-ins" !in said, said)
        assertTrue("$dropIn sets parentSettingsBehavior to \"merge\"" in said, said)
    }

    // A place splice did not read changes how the step is said, never whether a file that needs an edit is an item.
    @Test
    fun `places splice did not read are named in one wording, and every file that needs an edit is an item`() {
        for (state in states) {
            val facts = app(state, listOf(dropIn))
            val row = row(facts)!!
            assertEquals(row(app(state))!!.level, row.level, "the level is the file's, whatever else is named: $state")
            val named = "Claude Code may also take managed settings from $dropIn, which splice did not read: a value " +
                "set there may be the one Claude Code takes"
            assertTrue(named in row.said, row.said)
            assertTrue(row.fix.orEmpty().startsWith("where none of the places splice did not read sets "), row.fix)
            val item = items(facts).single()
            assertTrue(named in item && "what to do: where none of the places splice did not read sets " in item, item)
        }
    }

    @Test
    fun `a value that is not text is said to be so for either key, in the row and the item`() {
        val facts = app(read("""{"processWrapper": ["/bin/sh", "-c", "s3cret"], "parentSettingsBehavior": null}"""))
        val item = items(facts).single()
        assertTrue("sets processWrapper to a value that is not text" in item, item)
        assertTrue("sets parentSettingsBehavior to a value that is not text" in item, item)
        assertTrue("s3cret" !in item && "file splice read sets" !in item, item)
    }

    // Claude Code merges each drop-in over the main file, so the row and the item take the value the drop-in gives.
    @Test
    fun `a drop-in that sets merge over first-wins in the main file is a note and no item, and is named`() {
        val main = read("""{"processWrapper": "$launch", "parentSettingsBehavior": "first-wins"}""")
        val facts = app(main, dropIns = listOf(dropIn("""{"parentSettingsBehavior": "merge"}""")))
        val row = row(facts)!!
        assertEquals(ToolLevel.INFO, row.level)
        assertTrue("$dropIn sets parentSettingsBehavior to \"merge\"" in row.said, row.said)
        assertEquals(null, row.fix)
        assertEquals(emptyList<String>(), items(facts))
    }

    @Test
    fun `first-wins in a drop-in is the item to change, in that drop-in, not the main file`() {
        val main = read("""{"processWrapper": "$launch", "parentSettingsBehavior": "merge"}""")
        val facts = app(main, dropIns = listOf(dropIn("""{"parentSettingsBehavior": "first-wins"}""")))
        val row = row(facts)!!
        assertEquals(ToolLevel.WARN, row.level)
        assertTrue(row.fix.orEmpty().startsWith("make $dropIn hold parentSettingsBehavior \"merge\""), row.fix)
        assertTrue("make $managedFile" !in items(facts).single(), items(facts).toString())
    }

    @Test
    fun `Desktop not in app mode says nothing about the managed settings`() {
        val off = app(read("{}"), mode = DesktopMode.OFF)
        assertEquals(null, row(off))
        assertEquals(emptyList<String>(), items(off))
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
    fun `synced harnesses with the companion installed say nothing to do and report its version`() {
        val synced = setOf(McpHarness.AGY, McpHarness.CODEX)
        val facts = bare().copy(
            harness = HarnessFacts(AGY, CODEX, geminiShim = true, synced = synced),
            seatd = SeatdInstaller.Location(tmp.resolve("seatd-pipe"), "0.1.0"),
        )
        assertEquals(emptyList<Gap>(), CapabilityGaps(facts, false).afterUpgrade())
        val rows = CapabilityTools(facts).readings()
        assertTrue(rows.all { it.level == ToolLevel.OK })
        assertTrue("0.1.0" in rows.single { it.name == "seatd-pipe" }.said)
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
