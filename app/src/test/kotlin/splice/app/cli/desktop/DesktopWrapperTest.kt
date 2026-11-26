// desktop-antigravity: `splice desktop wrapper` says what Claude Code's managed settings need so Desktop's Code tab
// starts through splice (`processWrapper`) and Desktop's own limits still reach its Code sessions
// (`parentSettingsBehavior` "merge"). It says only what splice read: the file read, could not be read, or known to be
// absent; each key text, not text, or absent; every other place Claude Code reads that splice did not; and what
// Desktop does plainly only where splice read every place. `--check` says the same, with its exit code.
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
    private val unknown = "wherever managed settings exist, Desktop warns in every Code session unless the source " +
        "that wins sets parentSettingsBehavior to \"merge\""
    private val wanted = "wanted: processWrapper \"$launch\" and parentSettingsBehavior \"merge\""
    private val unreadFile = "$managed could not be read (no permission on its path, a link to nothing, or not a " +
        "JSON object), so splice does not say whether it exists or what it holds"

    // Every claim of what Desktop does, and every step that could be wrong while a source is unread.
    private val effects = listOf(
        "Desktop warns in every Code session, and its limits",
        "Desktop does not warn",
        "limits apply to the Code sessions",
        "starts through",
    )

    private fun wrapper(vararg args: String, osName: String = "Linux", env: EnvReader = this.env): Int {
        said.clear()
        errors.clear()
        val out = TerminalOutput { said += it }
        val settings = ManagedSettings(osName, platformDir = folder, policy = PlatformPolicy("Linux"))
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

    private fun claimsNoEffect() {
        for (effect in effects) assertFalse(effect in printed(), "\"$effect\" said while a source is unread: $said")
        assertTrue(unknown in printed(), printed())
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
        assertTrue("What to do: create it so it holds both keys." in said, printed())
        assertFalse(said.any { "starts through" in it || "does not warn" in it }, "no promise and no effect: $said")
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
            assertTrue(said.any { "splice could not look at" in it && "managed-settings.d" in it }, printed())
            claimsNoEffect()
        }
    }

    @Test
    fun `a file holding only other keys is edited in place, never copied, and what it holds is not printed`() {
        layLauncher()
        file("""{"permissions":{"deny":["Bash(rm:*)"]},"env":{"TOKEN":"s3cret-value"}}""")
        assertEquals(0, wrapper())
        val found = "$managed does not set processWrapper, and does not set parentSettingsBehavior."
        assertTrue(found in said, printed())
        assertTrue(said.any { "Desktop warns in every Code session, and its limits for them are not applied" in it })
        assertTrue(said.any { it.startsWith("What to do: edit it in place as an administrator so it holds both") })
        assertTrue("  For example: sudoedit '$managed'" in said, printed())
        assertFalse("install" in printed() || "Copy-Item" in printed(), "a copy would reset the owner and mode")
        assertFalse("s3cret-value" in printed() || "permissions" in printed(), "what the file holds is not printed")
        assertEquals(1, wrapper("--check"))
        assertTrue("splice: $found" in said, printed())
    }

    @Test
    fun `with both keys right and nothing else to read, print and check say so plainly and ask for nothing`() {
        layLauncher()
        file(bothRight)
        assertEquals(0, wrapper("--print"))
        assertTrue("$managed sets processWrapper to \"$launch\", and sets parentSettingsBehavior to \"merge\"." in said)
        val effect = "Claude Desktop's limits apply to the Code sessions it starts, and Desktop does not warn in them."
        assertTrue(effect.replaceFirstChar(Char::uppercase) in said, printed())
        assertFalse("What to do" in printed() || "Then quit" in printed() || "took" in printed(), printed())
        assertEquals(0, wrapper("--check"))
        val found = "$managed sets processWrapper to \"$launch\", and sets parentSettingsBehavior to \"merge\"."
        assertEquals(listOf("splice: $wanted.", "splice: $found", "splice: $effect"), said)
    }

    @Test
    fun `first-wins in the file is named as it is and the file is edited to hold both keys`() {
        layLauncher()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"first-wins"}""")
        assertEquals(0, wrapper("--check"), "the Code tab starts through splice")
        assertEquals("splice: $wanted.", said.first(), printed())
        assertTrue(said[1].contains("sets parentSettingsBehavior to \"first-wins\""), printed())
        assertTrue(said.any { "Desktop warns in every Code session" in it }, printed())
        assertTrue(said.any { it.startsWith("splice: what to do: edit it in place") }, printed())
    }

    @Test
    fun `another program's wrapper is named beside the one wanted, and check exits 1`() {
        layLauncher()
        file("""{"processWrapper":"/usr/local/bin/other","parentSettingsBehavior":"merge"}""")
        assertEquals(1, wrapper("--check"))
        assertEquals("splice: $wanted.", said.first(), printed())
        assertTrue("sets processWrapper to \"/usr/local/bin/other\"" in said[1], printed())
        assertFalse(said.any { "starts through" in it }, "what starts the Code tab is not splice's to say: $said")
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
            assertTrue(said.any { it.startsWith("What to do: look at it as an administrator") }, printed())
            assertFalse("install" in printed() || "Create" in printed() || "create" in printed(), printed())
            claimsNoEffect()
            assertEquals(1, wrapper("--check"))
            assertTrue("splice: $unreadFile." in said, printed())
            claimsNoEffect()
        }
    }

    @Test
    fun `a link to nothing is not absent, it could not be read, and nothing is created at it`() {
        layLauncher()
        Files.createDirectories(folder)
        Files.createSymbolicLink(managed, folder.resolve("gone.json"))
        assertEquals(0, wrapper("--print"))
        assertTrue("$unreadFile." in said, printed())
        assertFalse("nothing is at" in printed() || "sudo install" in printed(), printed())
        claimsNoEffect()
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
            assertFalse("does not set" in printed() || "nothing is at" in printed(), printed())
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
            assertFalse("nothing is at" in printed() || "sudo install" in printed(), printed())
            claimsNoEffect()
            assertEquals(1, wrapper("--check"))
            assertTrue("splice: $unreadFile." in said, printed())
        }
    }

    @Test
    fun `a drop-in that sets merge over a main file that sets first-wins leaves the effect open in both verbs`() {
        layLauncher()
        val dropIn = dropIn()
        file("""{"processWrapper":"$launch","parentSettingsBehavior":"first-wins"}""")
        val named = "Claude Code also reads $dropIn: splice did not read them, and a value set there may be the one " +
            "Claude Code takes."
        for (verb in listOf("--print", "--check")) {
            assertEquals(0, wrapper(verb))
            assertTrue(said.any { it.endsWith(named) }, printed())
            claimsNoEffect()
            assertFalse("change it to" in printed(), printed())
            val step = "check the places splice did not read first; then edit it in place"
            assertTrue(said.any { step in it }, printed())
        }
    }

    @Test
    fun `a drop-in and nothing at the main path, the drop-in named before any step, and the effect is open`() {
        layLauncher()
        val dropIn = dropIn()
        assertEquals(0, wrapper("--print"))
        val named = said.indexOfFirst { "Claude Code also reads $dropIn" in it }
        val step = said.indexOfFirst { it.startsWith("What to do: check the places splice did not read first") }
        assertTrue(named in 0 until step, printed())
        claimsNoEffect()
        assertEquals(1, wrapper("--check"))
        assertTrue(said.any { "Claude Code also reads $dropIn" in it }, printed())
        claimsNoEffect()
    }

    @Test
    fun `a drop-in folder splice cannot list is named as a place that may hold files, in every outcome of check`() {
        layLauncher()
        val dropIns = Files.createDirectories(folder.resolve("managed-settings.d"))
        val named = "splice could not look at $dropIns, where Claude Code may find more"
        for (text in listOf(bothRight, """{"processWrapper":"$launch"}""", "{ not json", null)) {
            if (text == null) Files.deleteIfExists(managed) else file(text)
            withLocked(dropIns) {
                for (verb in listOf("--print", "--check")) {
                    wrapper(verb)
                    assertTrue(said.any { named in it }, "$text: $said")
                    claimsNoEffect()
                }
            }
        }
    }

    @Test
    fun `a readable drop-in is named in every outcome of check, and no outcome claims an effect`() {
        layLauncher()
        val dropIn = dropIn()
        val files = listOf(bothRight, """{"processWrapper":"/usr/local/bin/other"}""", "{ not json", null)
        for (text in files) {
            if (text == null) Files.deleteIfExists(managed) else file(text)
            wrapper("--check")
            assertTrue(said.any { "Claude Code also reads $dropIn: splice did not read them" in it }, "$text: $said")
            claimsNoEffect()
        }
    }

    @Test
    fun `remote settings in Claude Code's own folder are named as a place splice did not read`() {
        layLauncher()
        file(bothRight)
        val remote = Files.createDirectories(home.resolve(".claude")).resolve("remote-settings.json")
        Files.writeString(remote, "{}")
        assertEquals(0, wrapper("--check"))
        assertTrue(said.any { "Claude Code also reads $remote: splice did not read them" in it }, printed())
        claimsNoEffect()
        assertFalse(said.any { "then quit" in it }, "nothing is to change, so nothing to reopen: $said")
        assertEquals(0, wrapper("--print"))
        assertTrue(said.any { "check that none of the places splice did not read sets either key" in it }, printed())
        assertFalse("sudoedit" in printed() || "sudo install" in printed(), "the file needs no change: $said")
    }

    @Test
    fun `remote settings are looked for where Claude Code is told to keep them, or named where splice cannot look`() {
        layLauncher()
        file(bothRight)
        val tree = Files.createDirectories(tmp.resolve("desktop-claude"))
        val remote = Files.writeString(tree.resolve("remote-settings.json"), "{}")
        val moved = EnvReader { if (it == "CLAUDE_CONFIG_DIR") tree.toString() else env(it) }
        wrapper("--check", env = moved)
        assertTrue(said.any { "Claude Code also reads $remote" in it }, printed())
        Files.delete(remote)
        withLocked(tree) {
            wrapper("--check", env = moved)
            assertTrue(said.any { "splice could not look at $remote" in it }, printed())
            claimsNoEffect()
        }
    }

    @Test
    fun `the launcher is the one under splice.toml's state folder, as the daemon lays it`() {
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
    fun `without the launcher laid, check exits 1 and both verbs say to run splice desktop on`() {
        file(bothRight)
        assertEquals(1, wrapper("--check"))
        assertTrue("splice: $launch is not laid yet: run `splice desktop on` first." in said, printed())
        assertEquals(0, wrapper("--print"))
        assertTrue(said.any { "not laid yet" in it }, printed())
    }

    @Test
    fun `on Windows it says how to put the file in place there, and edits one that exists in place`() {
        layLauncher()
        assertEquals(0, wrapper(osName = "Windows 11"))
        assertTrue(said.any { "Copy-Item managed-settings.json '$managed'" in it }, said.toString())
        assertFalse(said.any { "sudo" in it }, said.toString())
        file("""{"env":{}}""")
        assertEquals(0, wrapper(osName = "Windows 11"))
        assertTrue("  Edit it in an editor run as administrator." in said, printed())
        assertFalse("Copy-Item" in printed() || "sudo" in printed(), printed())
    }

    @Test
    fun `a flag it does not know is answered with its usage on standard error`() {
        assertEquals(2, wrapper("--nope"))
        assertTrue(said.isEmpty(), said.toString())
        assertTrue(errors.single().startsWith("usage: splice desktop wrapper"), errors.toString())
    }
}
