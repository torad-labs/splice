// desktop-antigravity: `splice desktop wrapper` says what Claude Code's managed settings need so Desktop's Code tab
// starts through splice (`processWrapper`) and Desktop's own limits still reach its Code sessions
// (`parentSettingsBehavior` "merge"). It says what is wanted and only what splice read: the file read, could not be
// read, or known to be absent; each key text, not text, or absent; the other places Claude Code may take managed
// settings from, named and not read; and what Desktop does, always as the condition, never as a fact. `--check` says
// the same, with its exit code.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedSettings
import splice.client.PlatformPolicy
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DesktopWrapperTest(@param:TempDir private val tmp: Path) {
    private val state = tmp.resolve("state")
    private val home = tmp.resolve("home")
    private val folder = tmp.resolve("etc/claude-code")
    private val managed = folder.resolve("managed-settings.json")
    private val said = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val config = tmp.resolve("splice.toml")
    private val env = EnvReader {
        when (it) {
            "SPLICE_STATE_DIR" -> state.toString()
            "SPLICE_CONFIG" -> config.toString()
            "HOME" -> home.toString()
            else -> null
        }
    }
    private val launch = DesktopPrograms(state).launch
    private val bothRight = """{"processWrapper":"$launch","parentSettingsBehavior":"merge"}"""
    private val wanted = "wanted: processWrapper \"$launch\" and parentSettingsBehavior \"merge\""
    private val condition = "managed settings exist, Desktop warns in every Code session unless the source that wins " +
        "sets parentSettingsBehavior to \"merge\""
    private val places = "Claude Code may also take managed settings from"
    private val unreadFile = "$managed could not be read (no permission on its path, a link to nothing, or not a " +
        "JSON object), so splice does not say whether it exists or what it holds"

    // Every way of saying what Desktop does as a fact, which no output may do.
    private val effects = listOf("Desktop does not warn", "limits apply", "its limits for them are not applied")

    private fun wrapper(vararg args: String, osName: String = "Linux", env: EnvReader = this.env): Int {
        said.clear()
        errors.clear()
        val out = TerminalOutput { said += it }
        val settings = ManagedSettings(osName, platformDir = folder, policy = PlatformPolicy(osName))
        return UserHome.within(home) {
            DesktopWrapper(env, out, TerminalOutput { errors += it }, osName, settings).run(args.toList())
        }
    }

    private fun file(text: String) {
        Files.createDirectories(folder)
        Files.writeString(managed, text)
    }

    private fun layLauncher() {
        Files.createDirectories(state)
        DesktopPrograms(state).lay()
    }

    private fun dropIn(text: String = """{"parentSettingsBehavior":"merge"}"""): Path {
        val dropIns = Files.createDirectories(folder.resolve("managed-settings.d"))
        return Files.writeString(dropIns.resolve("10-org.json"), text)
    }

    private fun printed(): String = said.joinToString("\n")

    /** The places line, as both verbs print it. */
    private fun placesLine(): String = said.single { places in it }

    /** No fact about what Desktop does, and the condition once. */
    private fun conditionOnly() {
        for (effect in effects) assertFalse(effect in printed(), "\"$effect\" said as a fact: $said")
        assertEquals(1, said.count { condition in it }, printed())
    }

    private fun withLocked(path: Path, block: () -> Unit) {
        Files.setPosixFilePermissions(path, emptySet())
        try {
            assumeFalse(Files.isReadable(path), "a user that can read anything cannot show this")
            block()
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `with nothing at the path, print shows both keys and the commands that create the file`() {
        layLauncher()
        assertEquals(0, wrapper("--print"))
        val shown = Json.parseToJsonElement(said.first { it.startsWith("{") })
        assertEquals(setOf("processWrapper", "parentSettingsBehavior"), shown.jsonObject.keys)
        assertEquals(launch.toString(), shown.jsonObject.getValue("processWrapper").jsonPrimitive.content)
        assertEquals("merge", shown.jsonObject.getValue("parentSettingsBehavior").jsonPrimitive.content)
        assertTrue("Nothing is at $managed." in said, printed())
        assertTrue(said.any { it.startsWith("What to do: check the places splice did not read first; then create it") })
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
            val wrong = listOf("othing is at", "sudo install", "create it").filter { it in printed() }
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
        val found = "$managed does not set processWrapper, and does not set parentSettingsBehavior."
        assertTrue(found in said, printed())
        assertTrue(said.any { "then edit it in place as an administrator so it holds both" in it }, printed())
        assertTrue("  For example: sudoedit '$managed'" in said, printed())
        assertFalse("install" in printed() || "Copy-Item" in printed(), "a copy would reset the owner and mode")
        assertFalse("s3cret-value" in printed() || "permissions" in printed(), "what the file holds is not printed")
        conditionOnly()
        assertEquals(1, wrapper("--check"))
        assertTrue("splice: $found" in said, printed())
    }

    // Splice never reads every place Claude Code may take managed settings from, so even a right file gets the
    // condition and a check of those places, never an all-clear, and nothing to edit or reopen.
    @Test
    fun `both keys right with the launcher laid exits 0, asks only for a check of the other places, claims nothing`() {
        layLauncher()
        file(bothRight)
        assertEquals(0, wrapper("--print"))
        assertTrue("$managed sets processWrapper to \"$launch\", and sets parentSettingsBehavior to \"merge\"." in said)
        assertTrue(
            "What to do: check that none of the places splice did not read sets either key to another value." in said,
        )
        assertFalse("sudoedit" in printed() || "Then quit" in printed() || "took" in printed(), printed())
        conditionOnly()
        assertEquals(0, wrapper("--check"))
        assertEquals("splice: $wanted.", said.first(), printed())
        assertFalse(said.any { "then quit" in it }, "nothing is to change, so nothing to reopen: $said")
        conditionOnly()
    }

    @Test
    fun `first-wins in the file is named as it is and the file is edited to hold both keys`() {
        layLauncher()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"first-wins"}""")
        assertEquals(0, wrapper("--check"), "the file names the laid launcher, which is all check confirms")
        assertEquals("splice: $wanted.", said.first(), printed())
        assertTrue(said[1].contains("sets parentSettingsBehavior to \"first-wins\""), printed())
        assertTrue(said.any { "then edit it in place" in it && it.startsWith("splice: what to do:") }, printed())
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
                    assertFalse(said.any { "does not set $key" in it }, "$value is not absent: $said")
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
            assertTrue(said.any { "then look at it as an administrator" in it }, printed())
            assertFalse("install" in printed() || "create it" in printed(), printed())
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
            assertFalse("does not set" in printed() || "othing is at" in printed(), printed())
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
    fun `a drop-in that sets merge over a main file that sets first-wins is named, with the condition only`() {
        layLauncher()
        val dropIn = dropIn()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"first-wins"}""")
        for (verb in listOf("--print", "--check")) {
            assertEquals(0, wrapper(verb))
            assertTrue("$dropIn" in placesLine(), printed())
            val tail = "which splice did not read: a value set there may be the one Claude Code takes."
            assertTrue(placesLine().endsWith(tail), printed())
            assertFalse("change it to" in printed(), printed())
            assertTrue(said.any { "check the places splice did not read first; then edit it in place" in it })
            conditionOnly()
        }
    }

    @Test
    fun `a drop-in and nothing at the main path, the drop-in named before any step, and a hidden one left out`() {
        layLauncher()
        val dropIn = dropIn()
        Files.writeString(dropIn.resolveSibling(".hidden.json"), "{}")
        assertEquals(0, wrapper("--print"))
        assertFalse(".hidden.json" in printed(), "Claude Code skips hidden drop-ins, and so does what splice names")
        val named = said.indexOfFirst { places in it && "$dropIn" in it }
        val step = said.indexOfFirst { it.startsWith("What to do: check the places splice did not read first") }
        assertTrue(named in 0 until step, printed())
        assertEquals(1, wrapper("--check"))
        assertTrue("$dropIn" in placesLine(), printed())
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
        assertTrue("  Edit it in an editor run as administrator." in said, printed())
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
