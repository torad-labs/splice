// Which processWrapper Claude Code's managed settings name, as DesktopFactsRead reads it: splice's only when the program
// is `desktop-launch`, none when the key is absent or names no program, another program otherwise.
package splice.app.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

    private fun wrapper(home: Path, settings: String?): WrapperState {
        val file = home.resolve("managed-settings.json")
        Files.deleteIfExists(file)
        settings?.let { Files.writeString(file, it) }
        return UserHome.within(home) {
            DesktopFactsRead(EnvReader { null }, osName = "Linux", managed = file).read(null).wrapper
        }
    }
}
