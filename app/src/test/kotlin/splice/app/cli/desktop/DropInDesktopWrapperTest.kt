// desktop-antigravity: `splice desktop wrapper` reads each managed-settings.d drop-in as Claude Code merges it over the
// main file, names the file that holds each key, and names the file to edit; a drop-in folder it cannot list, or a
// drop-in it cannot read, is a place it did not read, and the step is a condition on it.
package splice.app.cli.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DropInDesktopWrapperTest(@TempDir tmp: Path) : DesktopWrapperFixture(tmp) {
    @Test
    fun `a drop-in that sets merge over a main file that sets first-wins holds the key, so only the check is to do`() {
        layLauncher()
        val dropIn = dropIn()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"first-wins"}""")
        for (verb in listOf("--print", "--check")) {
            assertEquals(0, wrapper(verb))
            assertTrue(said.any { "$dropIn sets parentSettingsBehavior to \"merge\"" in it }, printed())
            assertFalse(said.any { places in it && "$dropIn" in it }, "splice read it: $said")
            checkOnly()
            conditionOnly()
        }
    }

    @Test
    fun `another wrapper in the main file under a drop-in with merge changes only the wrapper, in the main file`() {
        layLauncher()
        val dropIn = dropIn()
        file("""{"processWrapper":"/usr/bin/other","parentSettingsBehavior":"first-wins"}""")
        assertEquals(0, wrapper("--print"))
        assertTrue(said.any { "$dropIn sets parentSettingsBehavior to \"merge\"" in it }, printed())
        val edit = "sets processWrapper, make $managed hold processWrapper \"$launch\", editing it in place as an " +
            "administrator."
        assertTrue(step()!!.endsWith(edit), printed())
        assertTrue("  For example: sudoedit '$managed'" in said, printed())
        assertFalse("sudoedit '$dropIn'" in printed(), printed())
        restartOnlyAfterChange()
        assertEquals(1, wrapper("--check"))
    }

    @Test
    fun `a drop-in that sets both keys over another wrapper in the main file leaves only the check to do`() {
        layLauncher()
        dropIn(bothRight)
        file("""{"processWrapper":"/usr/bin/other","parentSettingsBehavior":"first-wins"}""")
        for (verb in listOf("--print", "--check")) {
            assertEquals(0, wrapper(verb))
            checkOnly()
        }
    }

    @Test
    fun `first-wins in a drop-in is changed in that named drop-in, and the main file is not the one to edit`() {
        layLauncher()
        val dropIn = dropIn("""{"parentSettingsBehavior":"first-wins"}""")
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"first-wins"}""")
        assertEquals(0, wrapper("--print"))
        assertTrue(said.any { "$dropIn sets parentSettingsBehavior to \"first-wins\"" in it }, printed())
        assertTrue("make $dropIn hold parentSettingsBehavior \"merge\", editing it in place" in step()!!, printed())
        assertFalse("make $managed" in printed() || "sudoedit '$managed'" in printed(), printed())
        assertTrue("  For example: sudoedit '$dropIn'" in said, printed())
        restartOnlyAfterChange()
        assertEquals(0, wrapper("--check"))
        assertTrue(said.last().contains("make $dropIn hold parentSettingsBehavior"), printed())
    }

    @Test
    fun `a drop-in with merge and nothing at the main path adds the wrapper there without a whole file copied over`() {
        layLauncher()
        val dropIn = dropIn()
        Files.writeString(dropIn.resolveSibling(".hidden.json"), "{}")
        assertEquals(0, wrapper("--print"))
        assertFalse(".hidden.json" in printed(), "Claude Code skips hidden drop-ins, and so does what splice names")
        assertTrue("Nothing is at $managed." in said, printed())
        assertTrue(said.any { it.startsWith("No managed settings file splice read sets processWrapper") }, printed())
        assertTrue(step()!!.endsWith("sets processWrapper, create $managed holding processWrapper \"$launch\"."))
        assertFalse("install" in printed() || "Copy-Item" in printed(), "no whole file over anything: $said")
        assertTrue("  For example: sudoedit '$managed'" in said, printed())
        restartOnlyAfterChange()
        assertEquals(1, wrapper("--check"))
    }

    @Test
    fun `a drop-in folder splice cannot list makes the step a condition on what is there, not an order`() {
        layLauncher()
        file("""{"processWrapper":"$launch"}""")
        val dropIns = Files.createDirectories(folder.resolve("managed-settings.d"))
        withLocked(dropIns) {
            assertEquals(0, wrapper("--print"))
            assertTrue("$dropIns" in placesLine(), printed())
            val condition = "What to do: where none of the places splice did not read sets parentSettingsBehavior, " +
                "make $managed hold parentSettingsBehavior \"merge\""
            assertTrue(step()!!.startsWith(condition), printed())
            restartOnlyAfterChange()
            assertEquals(0, wrapper("--check"))
            assertTrue(said.last().startsWith("splice: what to do: where none of the places"), printed())
            assertTrue(said.last().endsWith("after a change, quit Claude Desktop entirely and reopen it."), printed())
        }
    }

    @Test
    fun `a drop-in splice cannot read as an object is named as not read, and the main file still holds its keys`() {
        layLauncher()
        file(bothRight)
        val broken = dropIn("{ not json")
        assertEquals(0, wrapper("--check"))
        assertTrue("$broken" in placesLine(), printed())
        checkOnly()
    }
}
