// desktop-antigravity: `splice desktop wrapper` shows Claude Code's managed settings as they should read, the person's
// own keys kept and `processWrapper` naming splice's launcher, with the commands that put the file in place on this
// platform; `--check` says whether the file reads so now.
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

    private fun wrapper(vararg args: String, osName: String = "Linux"): Int {
        said.clear()
        errors.clear()
        val out = TerminalOutput { said += it }
        return DesktopWrapper(env, out, TerminalOutput { errors += it }, osName, managed).run(args.toList())
    }

    @Test
    fun `print shows the managed file with the launcher as its processWrapper, keeping its other keys`() {
        Files.createDirectories(managed.parent)
        Files.writeString(managed, """{"permissions":{"deny":["Bash(rm:*)"]}}""")
        assertEquals(0, wrapper())
        val shown = Json.parseToJsonElement(said.first { it.startsWith("{") }).jsonObject
        assertEquals(launch.toString(), shown.getValue("processWrapper").jsonPrimitive.content)
        assertTrue("permissions" in shown, shown.toString())
        val install = "sudo mkdir -p '${managed.parent}' && sudo install -m 0644 managed-settings.json '$managed'"
        assertTrue(said.any { install in it }, said.toString())
        assertFalse(said.any { "install -D" in it }, "no GNU-only flag: macOS's install has none")
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun `on Windows it says how to put the file in place there`() {
        assertEquals(0, wrapper(osName = "Windows 11"))
        assertTrue(said.any { "Copy-Item managed-settings.json '$managed'" in it }, said.toString())
        assertFalse(said.any { "sudo" in it }, said.toString())
    }

    @Test
    fun `a managed file that is not a JSON object is said, since its other keys are left out`() {
        Files.createDirectories(managed.parent)
        Files.writeString(managed, "{ not json")
        assertEquals(0, wrapper("--print"))
        assertTrue(errors.single().contains("is not a JSON object"), errors.toString())
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
    }

    @Test
    fun `a flag it does not know is answered with its usage on standard error`() {
        assertEquals(2, wrapper("--nope"))
        assertTrue(said.isEmpty(), said.toString())
        assertTrue(errors.single().startsWith("usage: splice desktop wrapper"), errors.toString())
    }
}
