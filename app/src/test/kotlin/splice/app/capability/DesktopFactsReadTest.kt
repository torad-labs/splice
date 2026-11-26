// What the doctor's managed settings row says of a real managed settings folder, as DesktopFactsRead reads it with
// Desktop on splice in app mode: splice's launcher only when it is the program named, a file read or one that could
// not be read, nothing at the path only where nothing is there, and every drop-in beside the file named as unread.
package splice.app.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.setup.DesktopMode
import splice.app.desktop.setup.DesktopPrograms
import splice.client.ManagedSettings
import splice.client.PlatformPolicy
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.diagnostics.doctor.ToolLevel
import splice.diagnostics.doctor.ToolReading
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DesktopFactsReadTest(@param:TempDir private val tmp: Path) {
    private val home = tmp.resolve("home")
    private val state = tmp.resolve("state")
    private val folder = tmp.resolve("managed")
    private val file = folder.resolve("managed-settings.json")
    private val launch = DesktopPrograms(state).launch

    @Test
    fun `splice's laid desktop-launch is the one program that is right, and nothing says what starts the Code tab`() {
        Files.createDirectories(state)
        DesktopPrograms(state).lay()
        val row = row("""{"processWrapper": "$launch", "parentSettingsBehavior": "merge"}""")
        assertEquals(ToolLevel.OK, row.level, row.said)
        val other = row("""{"processWrapper": "/usr/bin/not-desktop-launch", "parentSettingsBehavior": "merge"}""")
        assertEquals(ToolLevel.WARN, other.level)
        assertTrue("sets processWrapper to \"/usr/bin/not-desktop-launch\"" in other.said, other.said)
        assertTrue("starts through" !in row.said + other.said, other.said)
    }

    @Test
    fun `a file with only other keys is read and said not to set either key`() {
        val row = row("""{"permissions": {"deny": ["Bash(rm:*)"]}}""")
        assertTrue("$file does not set processWrapper, and does not set parentSettingsBehavior" in row.said, row.said)
        assertTrue("Bash(rm" !in row.said, "what the file holds is not said: ${row.said}")
    }

    @Test
    fun `merge is told apart from first-wins, from Merge and from null`() {
        assertTrue("sets parentSettingsBehavior to \"merge\"" in row("""{"parentSettingsBehavior": "merge"}""").said)
        assertTrue("to \"first-wins\"" in row("""{"parentSettingsBehavior": "first-wins"}""").said)
        val capital = row("""{"processWrapper": "$launch", "parentSettingsBehavior": "Merge"}""")
        assertEquals(ToolLevel.WARN, capital.level, capital.said)
        val nul = row("""{"parentSettingsBehavior": null}""").said
        assertTrue("sets parentSettingsBehavior to a value that is not text" in nul, nul)
    }

    @Test
    fun `nothing at the path is said so, and a file that is no JSON object is one splice could not read`() {
        assertTrue("nothing is at $file" in row(null).said, row(null).said)
        val broken = row("{ not json")
        assertTrue("$file could not be read" in broken.said, broken.said)
        assertTrue("nothing is at" !in broken.said && "does not set" !in broken.said, broken.said)
    }

    @Test
    fun `a file splice has no permission to read, or in a folder it cannot look into, is never said to be absent`() {
        row("""{"processWrapper": "$launch", "parentSettingsBehavior": "merge"}""")
        for (locked in listOf(file, folder)) {
            Files.setPosixFilePermissions(locked, emptySet())
            try {
                assumeFalse(Files.isReadable(locked), "a user that can read anything cannot show this")
                val said = row(null, keep = true).said
                assertTrue("$file could not be read" in said, said)
                assertTrue("nothing is at" !in said && "does not set" !in said, said)
            } finally {
                Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
            }
        }
    }

    @Test
    fun `a drop-in beside the file is named as unread, with or without a main file, and its keys are not read`() {
        val dropIn = Files.createDirectories(folder.resolve("managed-settings.d")).resolve("10-org.json")
        Files.writeString(dropIn, """{"parentSettingsBehavior": "merge"}""")
        for (text in listOf("""{"env": {}}""", null)) {
            val row = row(text)
            assertTrue("Claude Code also reads $dropIn: splice did not read them" in row.said, row.said)
            assertTrue("$file sets parentSettingsBehavior" !in row.said, "only the main file is read: ${row.said}")
            assertEquals(ToolLevel.INFO, row.level, row.said)
        }
    }

    private fun row(settings: String?, keep: Boolean = false): ToolReading {
        if (!keep) {
            Files.createDirectories(folder)
            Files.deleteIfExists(file)
            settings?.let { Files.writeString(file, it) }
        }
        val managed = ManagedSettings("Linux", platformDir = folder, policy = PlatformPolicy("Linux"))
        val env = EnvReader { if (it == "SPLICE_STATE_DIR") state.toString() else null }
        val desktop = UserHome.within(home) { DesktopFactsRead(env, osName = "Linux", managed = managed).read(null) }
        val facts = Capabilities(
            desktop.copy(mode = DesktopMode.APP),
            HarnessFacts(agy = null, codex = null, geminiShim = false, synced = emptySet()),
            HubFacts(linux = true, on = false, systemd = false),
            autoUpdate = null,
        )
        return CapabilityTools(facts).readings().single { it.name == "managed settings" }
    }
}
