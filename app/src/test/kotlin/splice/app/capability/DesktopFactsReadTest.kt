// Which processWrapper Claude Code's managed settings name, as DesktopFactsRead reads it: splice's only when the program
// is `desktop-launch`, none when the key is absent or names no program, another program otherwise.
package splice.app.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.ManagedSettings
import splice.client.PlatformPolicy
import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopFactsReadTest {

    @Test
    fun `splice's desktop-launch, as a path or a command line, is splice's`(@TempDir home: Path) {
        val path = """{"processWrapper": "/home/me/.splice/state/desktop-launch"}"""
        assertEquals(WrapperState.SPLICE, wrapper(home, path))
        assertEquals(WrapperState.SPLICE, wrapper(home, """{"processWrapper": ["/s/desktop-launch", "--x"]}"""))
    }

    @Test
    fun `a program whose name only ends like desktop-launch is another program`(@TempDir home: Path) {
        assertEquals(WrapperState.OTHER, wrapper(home, """{"processWrapper": "/usr/bin/not-desktop-launch"}"""))
    }

    @Test
    fun `an absent, blank or empty processWrapper is none`(@TempDir home: Path) {
        assertEquals(WrapperState.NONE, wrapper(home, """{"env": {}}"""))
        assertEquals(WrapperState.NONE, wrapper(home, """{"processWrapper": "  "}"""))
        assertEquals(WrapperState.NONE, wrapper(home, """{"processWrapper": []}"""))
        assertEquals(WrapperState.NONE, wrapper(home, null))
    }

    @Test
    fun `no managed file is no file, and a file with only other keys is a file that does not set the key`(
        @TempDir home: Path,
    ) {
        assertNull(facts(home, null).managedFile)
        val only = facts(home, """{"permissions": {"deny": ["Bash(rm:*)"]}}""").managedFile!!
        assertEquals(home.resolve("managed-settings.json"), only.file)
        assertTrue(only.parent.absent)
        assertTrue(only.elsewhere.isEmpty())
    }

    @Test
    fun `merge, another value and a null are told apart, whichever program the wrapper is`(@TempDir home: Path) {
        val launch = """"processWrapper": "/s/desktop-launch""""
        val other = """"processWrapper": "/usr/bin/other""""
        assertTrue(facts(home, """{$launch, "parentSettingsBehavior": "merge"}""").managedFile!!.parent.merges)
        assertTrue(facts(home, """{$other, "parentSettingsBehavior": "merge"}""").managedFile!!.parent.merges)
        val firstWins = facts(home, """{$launch, "parentSettingsBehavior": "first-wins"}""").managedFile!!.parent
        assertEquals("\"first-wins\"", firstWins.shown)
        assertFalse(firstWins.merges)
        assertTrue(facts(home, """{"parentSettingsBehavior": null}""").managedFile!!.parent.absent)
        assertFalse(facts(home, """{"parentSettingsBehavior": "Merge"}""").managedFile!!.parent.merges)
    }

    @Test
    fun `a file that is no JSON object is a file that says nothing of the key`(@TempDir home: Path) {
        assertTrue(facts(home, "{ not json").managedFile!!.parent.absent)
    }

    @Test
    fun `a drop-in beside the file is found and named as another source, not read`(@TempDir home: Path) {
        val dropIn = home.resolve("managed-settings.d/10-org.json")
        Files.createDirectories(dropIn.parent)
        Files.writeString(dropIn, """{"parentSettingsBehavior": "merge"}""")
        val read = facts(home, """{"env": {}}""").managedFile!!
        assertTrue(read.parent.absent, "only the one file is read")
        assertEquals(listOf(dropIn.toString()), read.elsewhere)
    }

    private fun wrapper(home: Path, settings: String?): WrapperState = facts(home, settings).wrapper

    private fun facts(home: Path, settings: String?): DesktopFacts {
        val file = home.resolve("managed-settings.json")
        Files.deleteIfExists(file)
        settings?.let { Files.writeString(file, it) }
        val managed = ManagedSettings("Linux", platformDir = home, policy = PlatformPolicy("Linux"))
        return UserHome.within(home) {
            DesktopFactsRead(EnvReader { null }, osName = "Linux", managed = managed).read(null)
        }
    }
}
