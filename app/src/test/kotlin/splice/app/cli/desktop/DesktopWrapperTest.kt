// desktop-antigravity: `splice desktop wrapper` says what Claude Code's managed settings need so Desktop's Code tab
// starts through splice (`processWrapper`) and Desktop's own limits still reach its Code sessions
// (`parentSettingsBehavior` "merge"). It says what is wanted and what splice read: the main file read, could not be
// read, or known to be absent; for each key the file, main or drop-in, whose value the merged files give; the places
// Claude Code may take managed settings from that splice could not read; what Desktop does, always as the condition;
// and the file to edit. `--check` says the same, with its exit code.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.setup.DesktopPrograms
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopWrapperTest(@TempDir tmp: Path) : DesktopWrapperFixture(tmp) {
    @Test
    fun `with nothing at the path, print shows both keys and the commands that create the file`() {
        layLauncher()
        assertEquals(0, wrapper("--print"))
        val shown = Json.parseToJsonElement(said.first { it.startsWith("{") })
        assertEquals(setOf("processWrapper", "parentSettingsBehavior"), shown.jsonObject.keys)
        assertEquals(launch.toString(), shown.jsonObject.getValue("processWrapper").jsonPrimitive.content)
        assertEquals("merge", shown.jsonObject.getValue("parentSettingsBehavior").jsonPrimitive.content)
        assertTrue("Nothing is at $managed." in said, printed())
        val create = "What to do: where none of the places splice did not read sets processWrapper or " +
            "parentSettingsBehavior, create $managed holding processWrapper \"$launch\" and parentSettingsBehavior"
        assertTrue(step()!!.startsWith(create), printed())
        restartOnlyAfterChange()
        assertFalse(said.any { "Code tab starts through" in it }, "no promise: $said")
        conditionOnly()
        val install = "  sudo mkdir -p '${managed.parent}' && sudo install -m 0644 managed-settings.json '$managed'"
        assertTrue(install in said, printed())
        assertFalse("install -D" in printed(), "no GNU-only flag: macOS's install has none")
        assertEquals(1, wrapper("--check"), "nothing names the launcher")
        assertEquals("splice: $wanted.", said.first(), "check says what is wanted first")
        assertTrue("splice: nothing is at $managed." in said, printed())
    }

    @Test
    fun `a link to nothing above the file is not absent, and nothing is created through it`() {
        layLauncher()
        Files.createDirectories(folder.parent)
        Files.createSymbolicLink(folder, tmp.resolve("gone"))
        for (verb in listOf("--print", "--check")) {
            wrapper(verb)
            assertTrue(said.any { it.endsWith("$unreadFile.") }, printed())
            val wrong = listOf("othing is at", "sudo install", "create ").filter { it in printed() }
            assertTrue(wrong.isEmpty(), printed())
            assertTrue("${folder.resolve("managed-settings.d")}" in placesLine(), printed())
            conditionOnly()
        }
    }

    @Test
    fun `a file holding only other keys is edited in place, never copied, and what it holds is not printed`() {
        layLauncher()
        file("""{"permissions":{"deny":["Bash(rm:*)"]},"env":{"TOKEN":"s3cret-value"}}""")
        assertEquals(0, wrapper())
        val found = "No managed settings file splice read sets processWrapper, and no managed settings file splice " +
            "read sets parentSettingsBehavior."
        assertTrue(found in said, printed())
        val edit = ", make $managed hold processWrapper \"$launch\" and parentSettingsBehavior \"merge\", editing it " +
            "in place as an administrator."
        assertTrue(step()!!.endsWith(edit), printed())
        assertTrue("  For example: sudoedit '$managed'" in said, printed())
        assertFalse("install" in printed() || "Copy-Item" in printed(), "a copy would reset the owner and mode")
        assertFalse("s3cret-value" in printed() || "permissions" in printed(), "what the file holds is not printed")
        conditionOnly()
        assertEquals(1, wrapper("--check"))
        assertTrue("splice: ${found.replaceFirstChar(Char::lowercase)}" in said, printed())
    }

    // Splice never reads every place Claude Code may take managed settings from, so even a right file gets the
    // condition, never an all-clear; with both keys as wanted the one step is to check the places it did not read,
    // with nothing to edit or reopen.
    @Test
    fun `both keys right with the launcher laid exits 0, asks only for a check of the other places, claims nothing`() {
        layLauncher()
        file(bothRight)
        assertEquals(0, wrapper("--print"))
        val found = "$managed sets processWrapper to \"$launch\", and $managed sets parentSettingsBehavior to " +
            "\"merge\"."
        assertTrue(found in said, printed())
        checkOnly()
        conditionOnly()
        assertEquals(0, wrapper("--check"))
        assertEquals("splice: $wanted.", said.first(), printed())
        checkOnly()
        conditionOnly()
    }

    @Test
    fun `first-wins in the file is named as it is and only that key is changed there`() {
        layLauncher()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"first-wins"}""")
        assertEquals(0, wrapper("--check"), "the file names the laid launcher, which is all check confirms")
        assertEquals("splice: $wanted.", said.first(), printed())
        assertTrue(said[1].contains("sets parentSettingsBehavior to \"first-wins\""), printed())
        val step = said.single { it.startsWith("splice: what to do:") }
        assertTrue("make $managed hold parentSettingsBehavior \"merge\", editing it in place" in step, printed())
        assertFalse("processWrapper," in step, "the wrapper is as wanted: $step")
        conditionOnly()
    }

    @Test
    fun `another program's wrapper is named beside the one wanted, check exits 1, and no effect is said`() {
        file("""{"processWrapper":"/usr/local/bin/other","parentSettingsBehavior":"merge"}""")
        for (laid in listOf(false, true)) {
            if (laid) layLauncher()
            assertEquals(1, wrapper("--check"))
            assertEquals("splice: $wanted.", said.first(), printed())
            assertTrue("sets processWrapper to \"/usr/local/bin/other\"" in said[1], printed())
            assertFalse(said.any { "Code tab starts through" in it }, "not splice's to say: $said")
            conditionOnly()
        }
    }

    @Test
    fun `null, a number, true, a list or an object for either key is a value that is not text, never shown`() {
        layLauncher()
        val secret = "s3cret-sentinel"
        for (value in listOf("null", "7", "true", """["/bin/sh","-c","$secret"]""", """{"env":{"T":"$secret"}}""")) {
            for (key in listOf("processWrapper", "parentSettingsBehavior")) {
                file("""{"$key":$value}""")
                for (verb in listOf("--print", "--check")) {
                    wrapper(verb)
                    assertTrue(said.any { "sets $key to a value that is not text" in it }, "$value: $said")
                    assertFalse(said.any { "file splice read sets $key" in it }, "$value is not absent: $said")
                    assertFalse(secret in printed(), printed())
                }
            }
        }
    }

    @Test
    fun `a file that is not a JSON object could not be read, for both keys, and nothing is created or copied`() {
        layLauncher()
        for (text in listOf("{ not json", "[1, 2]")) {
            file(text)
            assertEquals(0, wrapper("--print"))
            assertTrue("$unreadFile." in said, printed())
            assertTrue("as an administrator, editing it in place where it exists" in step()!!, printed())
            assertFalse("install" in printed() || "create " in printed(), printed())
            conditionOnly()
            assertEquals(1, wrapper("--check"))
            assertTrue("splice: $unreadFile." in said, printed())
        }
    }

    @Test
    fun `a link to nothing is not absent, it could not be read, and nothing is created at it`() {
        layLauncher()
        Files.createDirectories(folder)
        Files.createSymbolicLink(managed, folder.resolve("gone.json"))
        assertEquals(0, wrapper("--print"))
        assertTrue("$unreadFile." in said, printed())
        assertFalse("othing is at" in printed() || "sudo install" in printed(), printed())
        assertEquals(1, wrapper("--check"))
        assertTrue("splice: $unreadFile." in said, printed())
    }

    @Test
    fun `a file nobody may read could not be read, in print and check, and is not called missing its keys`() {
        layLauncher()
        file(bothRight)
        withLocked(managed) {
            assertEquals(0, wrapper("--print"))
            assertTrue("$unreadFile." in said, printed())
            assertFalse("file splice read sets" in printed() || "othing is at" in printed(), printed())
            restartOnlyAfterChange()
            assertEquals(1, wrapper("--check"))
            assertTrue("splice: $unreadFile." in said, printed())
        }
    }

    @Test
    fun `a folder splice cannot look into tells it nothing, so the file could not be read, not absent`() {
        layLauncher()
        file(bothRight)
        withLocked(folder) {
            assertEquals(0, wrapper("--print"))
            assertTrue("$unreadFile." in said, printed())
            assertFalse("othing is at" in printed() || "sudo install" in printed(), printed())
            assertEquals(1, wrapper("--check"))
            assertTrue("splice: $unreadFile." in said, printed())
        }
    }

    @Test
    fun `a drop-in folder splice cannot list is named in place of its files, in every outcome of check`() {
        layLauncher()
        val dropIns = Files.createDirectories(folder.resolve("managed-settings.d"))
        dropIn()
        for (text in listOf(bothRight, """{"processWrapper":"$launch"}""", "{ not json", null)) {
            if (text == null) Files.deleteIfExists(managed) else file(text)
            withLocked(dropIns) {
                for (verb in listOf("--print", "--check")) {
                    wrapper(verb)
                    assertTrue("$dropIns" in placesLine(), printed())
                    assertFalse("10-org.json" in printed(), "splice could not list it: $said")
                    conditionOnly()
                }
            }
        }
    }

    @Test
    fun `a directory named like a drop-in is named only as a place, never as one splice read`() {
        layLauncher()
        file(bothRight)
        val odd = Files.createDirectories(folder.resolve("managed-settings.d/not-a-file.json"))
        wrapper("--check")
        assertTrue("$odd" in placesLine(), printed())
        assertFalse(said.any { "also reads" in it }, "splice found it, and does not say Claude Code reads it: $said")
    }

    // The remote settings are named on every run, by the folder this process would give Claude Code, and never probed:
    // Desktop may give its Code sessions another folder, which splice cannot see.
    @Test
    fun `remote settings are named in the config folder this process sees, with or without a file there`() {
        layLauncher()
        file(bothRight)
        val tree = tmp.resolve("desktop-claude")
        val moved = EnvReader { if (it == "CLAUDE_CONFIG_DIR") tree.toString() else env(it) }
        for ((run, folder) in listOf(this.env to home.resolve(".claude"), moved to tree)) {
            wrapper("--check", env = run)
            val named = "remote-settings.json in Claude Code's config folder ($folder, unless CLAUDE_CONFIG_DIR " +
                "names another for a session)"
            assertTrue(named in placesLine(), printed())
            conditionOnly()
        }
    }

    @Test
    fun `the launcher is the one under the state folder splice's config names, as the daemon lays it`() {
        val declared = tmp.resolve("declared")
        Files.writeString(config, "[daemon]\nstate_dir = \"$declared\"\n")
        Files.createDirectories(declared)
        DesktopPrograms(declared).lay()
        val ownLaunch = DesktopPrograms(declared).launch
        file("""{"processWrapper":"$ownLaunch","parentSettingsBehavior":"merge"}""")
        assertEquals(0, wrapper("--check"))
        assertTrue(said.first().contains("processWrapper \"$ownLaunch\""), printed())
        assertFalse(said.any { "not laid" in it }, printed())
    }

    @Test
    fun `a folder where the launcher should be is not a laid launcher`() {
        Files.createDirectories(launch)
        file(bothRight)
        assertEquals(1, wrapper("--check"))
        assertTrue("splice: $launch is not laid yet: run `splice desktop on` first." in said, printed())
    }

    @Test
    fun `without the launcher laid, check exits 1, both verbs say to run splice desktop on, and no effect is said`() {
        file(bothRight)
        for ((verb, exit) in listOf("--check" to 1, "--print" to 0)) {
            assertEquals(exit, wrapper(verb))
            assertTrue(said.any { it.endsWith("$launch is not laid yet: run `splice desktop on` first.") }, printed())
            conditionOnly()
        }
    }

    @Test
    fun `on Windows it names both registry policy values and gives the commands for that system`() {
        layLauncher()
        assertEquals(0, wrapper(osName = "Windows 11"))
        for (hive in listOf("HKLM", "HKCU")) {
            assertTrue("the registry value $hive\\SOFTWARE\\Policies\\ClaudeCode\\Settings" in placesLine(), printed())
        }
        assertTrue(said.any { "Copy-Item managed-settings.json '$managed'" in it }, said.toString())
        assertFalse(said.any { "sudo" in it }, said.toString())
        file("""{"env":{}}""")
        assertEquals(0, wrapper(osName = "Windows 11"))
        assertTrue("  Edit each file named above in an editor run as administrator." in said, printed())
        assertFalse("Copy-Item" in printed() || "sudo" in printed(), printed())
    }

    @Test
    fun `on macOS it names the person's and the device's policy plists`() {
        layLauncher()
        assertEquals(0, wrapper(osName = "Mac OS X"))
        val user = System.getProperty("user.name")
        for (plist in listOf("$user/com.anthropic.claudecode.plist", "com.anthropic.claudecode.plist")) {
            assertTrue("/Library/Managed Preferences/$plist" in placesLine(), printed())
        }
    }

    @Test
    fun `a flag it does not know is answered with its usage on standard error`() {
        assertEquals(2, wrapper("--nope"))
        assertTrue(said.isEmpty(), said.toString())
        assertTrue(errors.single().startsWith("usage: splice desktop wrapper"), errors.toString())
    }
}
