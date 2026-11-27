// desktop-antigravity: what both `splice desktop wrapper` test classes share: a platform folder of their own, a state
// folder for splice's launcher, the command run with its output kept, and the checks every outcome is held to.
package splice.app.cli.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedSettings
import splice.client.PlatformPolicy
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

abstract class DesktopWrapperFixture(protected val tmp: Path) {
    protected val state = tmp.resolve("state")
    protected val home = tmp.resolve("home")
    protected val folder = tmp.resolve("etc/claude-code")
    protected val managed = folder.resolve("managed-settings.json")
    protected val said = mutableListOf<String>()
    protected val errors = mutableListOf<String>()
    protected val config = tmp.resolve("splice.toml")
    protected val env = EnvReader {
        when (it) {
            "SPLICE_STATE_DIR" -> state.toString()
            "SPLICE_CONFIG" -> config.toString()
            "HOME" -> home.toString()
            else -> null
        }
    }
    protected val launch = DesktopPrograms(state).launch
    protected val bothRight = """{"processWrapper":"$launch","parentSettingsBehavior":"merge"}"""
    protected val wanted = "wanted: processWrapper \"$launch\" and parentSettingsBehavior \"merge\""
    protected val condition = "managed settings exist, Desktop warns in every Code session unless the source that " +
        "wins sets parentSettingsBehavior to \"merge\""
    protected val places = "Claude Code may also take managed settings from"
    protected val checkSentence = "check that none of the places splice did not read sets either key to another value"
    protected val unreadFile = "$managed could not be read (no permission on its path, a link to nothing, or not a " +
        "JSON object), so splice does not say whether it exists or what it holds"

    // Every way of saying what Desktop does as a fact, which no output may do.
    protected val effects = listOf("Desktop does not warn", "limits apply", "its limits for them are not applied")

    protected fun wrapper(vararg args: String, osName: String = "Linux", env: EnvReader = this.env): Int {
        said.clear()
        errors.clear()
        val out = TerminalOutput { said += it }
        val settings = ManagedSettings(osName, platformDir = folder, policy = PlatformPolicy(osName))
        return UserHome.within(home) {
            DesktopWrapper(env, out, TerminalOutput { errors += it }, osName, settings).run(args.toList())
        }
    }

    protected fun file(text: String) {
        Files.createDirectories(folder)
        Files.writeString(managed, text)
    }

    protected fun layLauncher() {
        Files.createDirectories(state)
        DesktopPrograms(state).lay()
    }

    protected fun dropIn(text: String = """{"parentSettingsBehavior":"merge"}"""): Path {
        val dropIns = Files.createDirectories(folder.resolve("managed-settings.d"))
        return Files.writeString(dropIns.resolve("10-org.json"), text)
    }

    protected fun printed(): String = said.joinToString("\n")

    /** The step, as --print says it; null where there is none. */
    protected fun step(): String? = said.singleOrNull { it.startsWith("What to do: ") }

    /** Nothing to change: the one step is the check of the places splice did not read, with no edit and no restart,
     *  in either verb. */
    protected fun checkOnly() {
        val step = said.filter { "what to do" in it.lowercase() }
        assertEquals(1, step.size, printed())
        assertTrue(step.single().lowercase().endsWith("what to do: $checkSentence."), printed())
        assertTrue(said.none { "sudoedit" in it || "reopen" in it || "shows what to put where" in it }, printed())
    }

    /** The restart, only as following a change. */
    protected fun restartOnlyAfterChange() {
        val reopen = said.filter { "reopen" in it }
        assertTrue(reopen.isNotEmpty() && reopen.all { "fter a change, quit Claude Desktop entirely" in it }, printed())
    }

    /** The places line, as both verbs print it. */
    protected fun placesLine(): String = said.single { places in it }

    /** No fact about what Desktop does, and the condition once. */
    protected fun conditionOnly() {
        for (effect in effects) assertFalse(effect in printed(), "\"$effect\" said as a fact: $said")
        assertEquals(1, said.count { condition in it }, printed())
    }

    protected fun withLocked(path: Path, block: () -> Unit) {
        Files.setPosixFilePermissions(path, emptySet())
        try {
            assumeFalse(Files.isReadable(path), "a user that can read anything cannot show this")
            block()
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
}
