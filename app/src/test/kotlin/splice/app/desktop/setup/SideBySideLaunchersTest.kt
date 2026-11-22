package splice.app.desktop.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class SideBySideLaunchersTest {
    @Test
    fun `Linux installs desktop entry in XDG applications dir and keeps custom files`(
        @TempDir home: Path,
    ) {
        val env = EnvReader { null }
        UserHome.within(home) {
            val launchers = SideBySideLaunchers(env, osName = "Linux")
            val installed = launchers.installDesktopApp()
            assertTrue(installed is AppLauncherInstall.Installed, installed.toString())
            val path = launchers.desktopLauncherPath()
            assertTrue(path.toString().endsWith("/.local/share/applications/ai.torad.claude-splice.desktop"))
            val text = Files.readString(path)
            assertTrue(text.contains("Name=Claude (Splice)"), text)
            assertTrue(text.contains("launch claude-desktop %U"), text)

            assertTrue(launchers.removeIfManaged())
            assertFalse(Files.exists(path))

            // User-authored file without splice marker is never overwritten or removed
            Files.createDirectories(path.parent)
            Files.writeString(path, "[Desktop Entry]\nName=Custom\n")
            val skipped = launchers.installDesktopApp()
            assertTrue(skipped is AppLauncherInstall.Skipped, skipped.toString())
            assertFalse(launchers.removeIfManaged())
            assertTrue(Files.exists(path))
        }
    }

    @Test
    fun `macOS installs Claude (Splice) app bundle in user Applications folder and cleans it up`(@TempDir home: Path) {
        val env = EnvReader { null }
        UserHome.within(home) {
            val launchers = SideBySideLaunchers(env, osName = "Mac OS X")
            val installed = launchers.installDesktopApp()
            assertTrue(installed is AppLauncherInstall.Installed, installed.toString())
            val app = launchers.desktopLauncherPath()
            assertTrue(app.toString().endsWith("/Applications/Claude (Splice).app"))
            assertTrue(Files.isRegularFile(app.resolve("Contents/Info.plist")))
            val exec = app.resolve("Contents/MacOS/claude-splice")
            assertTrue(Files.isRegularFile(exec))
            assertTrue(Files.readString(exec).contains("launch claude-desktop"))

            assertTrue(launchers.removeIfManaged())
            assertFalse(Files.exists(app))
        }
    }

    @Test
    fun `prepareProfile configures isolated claude-splice folder without touching default Claude`(@TempDir home: Path) {
        val env = EnvReader { null }
        UserHome.within(home) {
            val defaultClaudeDir = home.resolve(".config/Claude-3p/configLibrary")
            val desktop = SideBySideDesktop(
                env = env,
                out = TerminalOutput {},
                err = TerminalOutput {},
                osName = "Linux",
            )
            val profile = desktop.prepareProfile("http://127.0.0.1:3096/door")
            assertEquals(home.resolve(".config/claude-splice"), profile)
            assertFalse(Files.exists(defaultClaudeDir), "default Claude Desktop config must stay untouched")
            val isolatedLib = profile.resolve("Claude-3p/configLibrary")
            assertTrue(Files.isRegularFile(isolatedLib.resolve("_meta.json")))
            val configText = Files.readString(isolatedLib.resolve("splice-side-by-side.json"))
            assertTrue(configText.contains("http://127.0.0.1:3096/door"), configText)
        }
    }
}
