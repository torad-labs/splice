// desktop-antigravity: `splice desktop wrapper` shows Claude Code's managed settings as they should read, the person's
// own keys kept and `processWrapper` naming splice's launcher, and `--check` says whether the file reads so now.
package splice.app.cli.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.setup.DesktopPrograms
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopWrapperTest(@param:TempDir private val tmp: Path) {
    private val state = tmp.resolve("state")
    private val managed = tmp.resolve("etc/claude-code/managed-settings.json")
    private val said = mutableListOf<String>()
    private val env = EnvReader { if (it == "SPLICE_STATE_DIR") state.toString() else null }
    private val launch = DesktopPrograms(state).launch

    private fun wrapper(vararg args: String): Int {
        said.clear()
        return DesktopWrapper(env, TerminalOutput { said += it }, managedFile = managed).run(args.toList())
    }

    @Test
    fun `print shows the managed file with the launcher as its processWrapper, keeping its other keys`() {
        Files.createDirectories(managed.parent)
        Files.writeString(managed, """{"permissions":{"deny":["Bash(rm:*)"]}}""")
        assertEquals(0, wrapper())
        val json = said.first { it.startsWith("{") }
        val shown = Json.parseToJsonElement(json).jsonObject
        assertEquals(launch.toString(), shown.getValue("processWrapper").jsonPrimitive.content)
        assertTrue("permissions" in shown, json)
        assertTrue(said.any { "sudo install -D -m 0644 managed-settings.json '$managed'" in it }, said.toString())
    }

    @Test
    fun `check passes only once the launcher is laid and the managed file names it`() {
        assertEquals(1, wrapper("--check"))
        assertTrue(said.single().contains("is not laid"), said.toString())

        Files.createDirectories(state)
        DesktopPrograms(state).lay()
        assertEquals(1, wrapper("--check"))
        assertTrue(said.single().contains("does not exist"), said.toString())

        Files.createDirectories(managed.parent)
        Files.writeString(managed, """{"processWrapper":"/usr/local/bin/other"}""")
        assertEquals(1, wrapper("--check"))
        assertTrue(said.single().contains("not $launch"), said.toString())

        Files.writeString(managed, """{"processWrapper":"$launch"}""")
        assertEquals(0, wrapper("--check"))
        assertEquals("splice: Claude Code's processWrapper is $launch", said.single())
        assertEquals(2, wrapper("--nope"))
    }
}
