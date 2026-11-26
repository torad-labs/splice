// desktop-antigravity: `splice desktop wrapper` says what Claude Code's managed settings need so Desktop's Code tab
// starts through splice (`processWrapper`) and Desktop's own limits still reach its Code sessions
// (`parentSettingsBehavior` "merge"). For each of the two keys it says one of three things: already right, absent and
// to be added, or set to a value and to be changed. `--check` says the same of the file that is there, with its exit
// code.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedSettings
import splice.client.PlatformPolicy
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopWrapperTest(@param:TempDir private val tmp: Path) {
    private val state = tmp.resolve("state")
    private val managed = tmp.resolve("etc/claude-code/managed-settings.json")
    private val said = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val env = EnvReader { if (it == "SPLICE_STATE_DIR") state.toString() else null }
    private val launch = DesktopPrograms(state).launch
    private val allClear =
        "splice: Claude Code's processWrapper is $launch and parentSettingsBehavior is \"merge\" in $managed"

    private fun wrapper(vararg args: String, osName: String = "Linux"): Int {
        said.clear()
        errors.clear()
        val out = TerminalOutput { said += it }
        val settings = ManagedSettings(osName, platformDir = managed.parent, policy = PlatformPolicy("Linux"))
        return DesktopWrapper(env, out, TerminalOutput { errors += it }, osName, settings).run(args.toList())
    }

    private fun file(text: String) {
        Files.createDirectories(managed.parent)
        Files.writeString(managed, text)
    }

    private fun layLauncher() {
        Files.createDirectories(state)
        DesktopPrograms(state).lay()
    }

    private fun dropIn(): Path {
        val folder = Files.createDirectories(managed.parent.resolve("managed-settings.d"))
        return Files.writeString(folder.resolve("10-org.json"), """{"parentSettingsBehavior":"first-wins"}""")
    }

    private fun printed(): String = said.joinToString("\n")

    @Test
    fun `print with no managed file shows the whole file with both keys and says why the second is there`() {
        assertEquals(0, wrapper("--print"))
        assertTrue(said.first().contains("$managed does not exist yet. Create it so it reads:"), printed())
        val shown = Json.parseToJsonElement(said.first { it.startsWith("{") }).jsonObject
        assertEquals(setOf("processWrapper", "parentSettingsBehavior"), shown.keys)
        assertEquals(launch.toString(), shown.getValue("processWrapper").jsonPrimitive.content)
        assertEquals("merge", shown.getValue("parentSettingsBehavior").jsonPrimitive.content)
        assertTrue("limits in Claude Desktop's own policy still apply to the Code sessions it starts" in printed())
        assertTrue("does not warn in each of them." in printed(), printed())
        val install = "sudo mkdir -p '${managed.parent}' && sudo install -m 0644 managed-settings.json '$managed'"
        assertTrue(install in printed(), printed())
        assertFalse("install -D" in printed(), "no GNU-only flag: macOS's install has none")
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `print for a file holding only other keys says to add both, in place, and offers no copy`() {
        file("""{"permissions":{"deny":["Bash(rm:*)"]},"env":{"TOKEN":"s3cret-value"}}""")
        assertEquals(0, wrapper())
        assertTrue("$managed holds settings already. Do not replace it with a copy" in printed(), printed())
        assertTrue("  processWrapper: add \"processWrapper\": \"$launch\"" in printed(), printed())
        assertTrue("  parentSettingsBehavior: add \"parentSettingsBehavior\": \"merge\"" in printed(), printed())
        assertTrue("Edit it in place, as an administrator (for example `sudoedit '$managed'`)" in printed(), printed())
        assertFalse("install" in printed(), "a copy would reset the owner and mode: $said")
        assertFalse("Copy-Item" in printed(), printed())
        assertFalse("s3cret-value" in printed() || "permissions" in printed(), "what the file holds is not printed")
        assertTrue("Then quit Claude Desktop entirely and reopen it." in printed(), printed())
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `print for first-wins already in the file says it is changed, not added, and is the administrator's choice`() {
        file("""{"parentSettingsBehavior":"first-wins"}""")
        assertEquals(0, wrapper("--print"))
        assertTrue("  parentSettingsBehavior: set to \"first-wins\"; change it to \"merge\"" in printed(), printed())
        assertFalse("add \"parentSettingsBehavior\"" in printed(), printed())
        assertTrue("It is set to \"first-wins\" there." in printed(), printed())
        assertTrue("limits under the" in printed() && "That is the" in printed(), printed())
        assertTrue("administrator's choice." in printed(), printed())
    }

    @Test
    fun `print for another program's wrapper says it is changed, and leaves a merge already there alone`() {
        file("""{"processWrapper":"/usr/local/bin/other","parentSettingsBehavior":"merge"}""")
        assertEquals(0, wrapper())
        val changed = "  processWrapper: set to \"/usr/local/bin/other\"; change it to \"$launch\""
        assertTrue(changed in printed(), printed())
        assertTrue("  parentSettingsBehavior: already \"merge\"; leave it" in printed(), printed())
        assertFalse("administrator's choice" in printed(), "nothing to choose where merge is already set")
    }

    @Test
    fun `print with both keys already right says so and asks for nothing`() {
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"merge","env":{"A":"1"}}""")
        assertEquals(0, wrapper("--print"))
        assertTrue("$managed already has both keys right" in printed(), printed())
        assertTrue("There is nothing to add." in printed(), printed())
        assertFalse("Edit it" in printed() || "add " in printed().replace("There is nothing to add.", ""), printed())
        assertFalse("Then quit" in printed(), printed())
        assertTrue("$launch is not laid yet: run `splice desktop on` first." in printed(), printed())
        layLauncher()
        assertEquals(0, wrapper("--print"))
        assertFalse("not laid yet" in printed(), printed())
    }

    @Test
    fun `print for a file that is no JSON object says it could not read it, for both keys, and offers no copy`() {
        file("{ not json")
        assertEquals(0, wrapper("--print"))
        val unread = "$managed exists but splice could not read it (no permission, or it is not a JSON object)"
        assertTrue(unread in printed(), printed())
        assertTrue("cannot say which of these the file already has." in printed(), printed())
        assertTrue("\"processWrapper\": \"$launch\"" in printed(), printed())
        assertTrue("\"parentSettingsBehavior\": \"merge\"" in printed(), printed())
        assertFalse("install" in printed() || "Copy-Item" in printed(), printed())
        assertFalse("add or change" in printed() || "already has both" in printed(), printed())
    }

    @Test
    fun `a file nobody may read is said the same way in print and check, and not as missing its keys`() {
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"merge"}""")
        Files.setPosixFilePermissions(managed, emptySet())
        assumeFalse(Files.isReadable(managed), "a user that can read any file cannot show this")
        layLauncher()
        assertEquals(0, wrapper("--print"))
        assertTrue("$managed exists but splice could not read it" in printed(), printed())
        assertFalse("add" in printed().replace("add or change", "") || "change it to" in printed(), printed())
        assertEquals(1, wrapper("--check"))
        val line = said.first()
        val unread = "splice: not set up: $managed could not be read (no permission, or it is not a JSON object)"
        assertTrue(unread in line, line)
        assertTrue("cannot say what it holds for processWrapper or parentSettingsBehavior" in line, line)
        assertFalse("names no" in line || "does not set" in line || "does not exist" in line, line)
    }

    @Test
    fun `check of a file that is no JSON object says it could not read it and exits 1`() {
        layLauncher()
        file("[1, 2]")
        assertEquals(1, wrapper("--check"))
        assertTrue("could not be read" in said.single(), printed())
        assertFalse("names no" in said.single(), printed())
    }

    @Test
    fun `with no main file and a drop-in present, print and check name the drop-in`() {
        layLauncher()
        val dropIn = dropIn()
        assertEquals(0, wrapper("--print"))
        assertTrue("$managed does not exist yet. Create it so it reads:" in printed(), printed())
        assertTrue("Claude Code also reads $dropIn, which splice did not read: it may set" in printed(), printed())
        assertTrue("wins over this file." in printed(), printed())
        assertEquals(1, wrapper("--check"))
        assertTrue("splice: not set up: $managed does not exist" in said.first(), printed())
        val named = "Claude Code reads $dropIn, which splice did not read: it may set these keys"
        assertTrue(named in printed(), printed())
        assertTrue("unless one sets parentSettingsBehavior to \"merge\"" in printed(), printed())
    }

    @Test
    fun `print names another source Claude Code reads that it did not read`() {
        val dropIn = dropIn()
        file("""{"processWrapper":"$launch"}""")
        assertEquals(0, wrapper())
        assertTrue("Claude Code also reads $dropIn, which splice did not read:" in printed(), printed())
        assertTrue("may be set there" in printed(), printed())
    }

    @Test
    fun `on Windows it says how to put the file in place there, and edits one that exists in place`() {
        assertEquals(0, wrapper(osName = "Windows 11"))
        assertTrue(said.any { "Copy-Item managed-settings.json '$managed'" in it }, said.toString())
        assertFalse(said.any { "sudo" in it }, said.toString())
        file("""{"env":{}}""")
        assertEquals(0, wrapper(osName = "Windows 11"))
        assertTrue("Edit it in place, in an editor run as administrator" in printed(), printed())
        assertFalse("Copy-Item" in printed() || "sudo" in printed(), printed())
    }

    @Test
    fun `check names what is missing and exits 1 only where the wrapper would not start`() {
        assertEquals(1, wrapper("--check"))
        assertTrue(said.single().contains("is not laid"), said.toString())

        layLauncher()
        assertEquals(1, wrapper("--check"))
        assertTrue(said.single().contains("$managed does not exist"), said.toString())

        file("""{"parentSettingsBehavior":"merge"}""")
        assertEquals(1, wrapper("--check"))
        assertTrue(said.single().contains("names no processWrapper"), said.toString())

        file("""{"processWrapper":"/usr/local/bin/other","parentSettingsBehavior":"merge"}""")
        assertEquals(1, wrapper("--check"))
        assertTrue(said.single().contains("not $launch"), said.toString())
    }

    @Test
    fun `check with both keys right exits 0 and says so in one line`() {
        layLauncher()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"merge"}""")
        assertEquals(0, wrapper("--check"))
        assertEquals(allClear, said.single())
    }

    @Test
    fun `check with the wrapper in place and no merge exits 0 and its first line says the whole state`() {
        layLauncher()
        file("""{"processWrapper":"$launch","permissions":{"deny":[]}}""")
        assertEquals(0, wrapper("--check"), "the wrapper works, so it is not reported as broken")
        val first = said.first()
        assertNotEquals(allClear, first)
        assertEquals(
            "splice: Claude Code's processWrapper is $launch, but $managed does not set parentSettingsBehavior: " +
                "Desktop warns in every Code session and its limits for Code are not applied",
            first,
        )
        assertTrue("add \"parentSettingsBehavior\": \"merge\" to it" in printed(), printed())
        assertFalse(said.any { "not set up" in it }, printed())
    }

    @Test
    fun `check with first-wins in the file exits 0, names the value, and does not ask for an addition`() {
        layLauncher()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"first-wins"}""")
        assertEquals(0, wrapper("--check"))
        val first = said.first()
        assertNotEquals(allClear, first)
        val sets = "$managed sets parentSettingsBehavior to \"first-wins\": Desktop warns in every Code session"
        assertTrue(sets in first, first)
        assertTrue("changing it to \"merge\"" in printed() && "administrator's choice" in printed(), printed())
        assertFalse("add " in printed(), "the key is there already; adding it would write it twice: $said")
    }

    @Test
    fun `check names a source it did not read, in the all-clear case and the missing-key case`() {
        layLauncher()
        val dropIn = dropIn()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"merge"}""")
        assertEquals(0, wrapper("--check"))
        assertEquals(allClear, said.first())
        assertTrue("Claude Code also reads $dropIn, which splice did not read" in printed(), printed())
        file("""{"processWrapper":"$launch"}""")
        assertEquals(0, wrapper("--check"))
        assertTrue("does not set parentSettingsBehavior" in said.first(), printed())
        assertTrue("which splice did not read; a setting" in printed(), printed())
        assertTrue("may win over $managed's" in printed(), printed())
    }

    @Test
    fun `a flag it does not know is answered with its usage on standard error`() {
        assertEquals(2, wrapper("--nope"))
        assertTrue(said.isEmpty(), said.toString())
        assertTrue(errors.single().startsWith("usage: splice desktop wrapper"), errors.toString())
    }
}
