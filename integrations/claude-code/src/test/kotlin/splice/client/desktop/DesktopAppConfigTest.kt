// Verifies Claude Desktop 3P library, beside() discovery, and managed-policy path resolution across Linux and macOS.
package splice.client.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopAppConfigTest {
    private fun envOf(vararg entries: Pair<String, String>): EnvReader {
        val map = entries.toMap()
        return EnvReader { map[it] }
    }

    @Test
    fun `linux default resolves under dot config`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val config = DesktopAppConfig(envOf(HOME_KEY to home.toString()), osName = LINUX_OS)

        assertEquals(home.resolve(".config/Claude-3p/configLibrary"), config.library)
        assertEquals(home.resolve(".config/Claude-3p/configLibrary/_meta.json"), config.meta)
    }

    @Test
    fun `macos default resolves under Library Application Support`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val config = DesktopAppConfig(envOf(HOME_KEY to home.toString()), osName = MAC_OS)

        assertEquals(home.resolve("Library/Application Support/Claude-3p/configLibrary"), config.library)
        assertEquals(home.resolve("Library/Application Support/Claude-3p/configLibrary/_meta.json"), config.meta)
    }

    @Test
    fun `explicit XDG_CONFIG_HOME overrides default on linux and macos`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val xdg = tmp.resolve("custom-xdg")
        val env = envOf(HOME_KEY to home.toString(), XDG_KEY to xdg.toString())

        assertEquals(
            xdg.resolve(RELATIVE_LIBRARY),
            DesktopAppConfig(env, osName = LINUX_OS).library,
        )
        assertEquals(
            xdg.resolve(RELATIVE_LIBRARY),
            DesktopAppConfig(env, osName = MAC_OS).library,
        )
    }

    @Test
    fun `blank XDG_CONFIG_HOME falls back to platform default`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val env = envOf(HOME_KEY to home.toString(), XDG_KEY to "   ")

        assertEquals(
            home.resolve(".config").resolve(RELATIVE_LIBRARY),
            DesktopAppConfig(env, osName = LINUX_OS).library,
        )
        assertEquals(
            home.resolve("Library/Application Support").resolve(RELATIVE_LIBRARY),
            DesktopAppConfig(env, osName = MAC_OS).library,
        )
    }

    @Test
    fun `CLAUDE_USER_DATA_DIR resolves directly to configLibrary without Claude-3p`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val xdg = tmp.resolve("xdg")
        val userData = tmp.resolve("custom-user-data")
        val env = envOf(
            HOME_KEY to home.toString(),
            XDG_KEY to xdg.toString(),
            USER_DATA_KEY to userData.toString(),
        )

        assertEquals(userData.resolve("configLibrary"), DesktopAppConfig(env, osName = LINUX_OS).library)
        assertEquals(userData.resolve("configLibrary"), DesktopAppConfig(env, osName = MAC_OS).library)
    }

    @Test
    fun `blank CLAUDE_USER_DATA_DIR falls back to home resolution`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val env = envOf(HOME_KEY to home.toString(), USER_DATA_KEY to "  ")

        assertEquals(
            home.resolve("Library/Application Support").resolve(RELATIVE_LIBRARY),
            DesktopAppConfig(env, osName = MAC_OS).library,
        )
    }

    @Test
    fun `beside discovers sibling libraries on linux and ignores non-library folders`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val configRoot = home.resolve(".config")
        val alphaLib = Files.createDirectories(configRoot.resolve("alpha").resolve(RELATIVE_LIBRARY))
        val betaLib = Files.createDirectories(configRoot.resolve("claude-splice").resolve(RELATIVE_LIBRARY))
        Files.createDirectories(configRoot.resolve("unrelated"))

        val found = DesktopAppConfig(envOf(HOME_KEY to home.toString()), osName = LINUX_OS).beside()

        assertEquals(listOf(alphaLib, betaLib), found.map(DesktopAppConfig::library))
        assertEquals(listOf(LINUX_OS, LINUX_OS), found.map(DesktopAppConfig::osName))
    }

    @Test
    fun `beside discovers sibling libraries on macos under Library Application Support`(@TempDir tmp: Path) {
        val home = tmp.resolve("home")
        val appSupport = home.resolve("Library/Application Support")
        val sideBySideLib = Files.createDirectories(appSupport.resolve("claude-splice").resolve(RELATIVE_LIBRARY))
        val secondLib = Files.createDirectories(appSupport.resolve("work-desktop").resolve(RELATIVE_LIBRARY))
        Files.createDirectories(appSupport.resolve("Claude"))

        val env = envOf(
            HOME_KEY to home.toString(),
            USER_DATA_KEY to tmp.resolve("parent-user-data").toString(),
        )
        val found = DesktopAppConfig(env, osName = MAC_OS).beside()

        assertEquals(listOf(sideBySideLib, secondLib), found.map(DesktopAppConfig::library))
        assertEquals(listOf(MAC_OS, MAC_OS), found.map(DesktopAppConfig::osName))
    }

    @Test
    fun `beside returns empty when config home does not exist`(@TempDir tmp: Path) {
        val missingHome = tmp.resolve("missing-home")

        assertEquals(
            emptyList<DesktopAppConfig>(),
            DesktopAppConfig(envOf(HOME_KEY to missingHome.toString()), osName = MAC_OS).beside(),
        )
    }

    @Test
    fun `managedFiles uses etc file on linux and MDM plists on macos`() {
        val env = envOf()
        val linux = DesktopAppConfig(env, osName = LINUX_OS, userName = "alice")
        val macWithUser = DesktopAppConfig(env, osName = MAC_OS, userName = "alice")
        val macNoUser = DesktopAppConfig(env, osName = MAC_OS, userName = "")

        assertEquals(listOf(Path.of(DESKTOP_MANAGED_FILE)), linux.managedFiles)
        assertEquals(
            listOf(
                Path.of("/Library/Managed Preferences/com.anthropic.claudefordesktop.plist"),
                Path.of("/Library/Managed Preferences/alice/com.anthropic.claudefordesktop.plist"),
            ),
            macWithUser.managedFiles,
        )
        assertEquals(
            listOf(Path.of("/Library/Managed Preferences/com.anthropic.claudefordesktop.plist")),
            macNoUser.managedFiles,
        )
    }
}

private const val HOME_KEY = "HOME"
private const val XDG_KEY = "XDG_CONFIG_HOME"
private const val USER_DATA_KEY = "CLAUDE_USER_DATA_DIR"
private const val LINUX_OS = "Linux"
private const val MAC_OS = "Mac OS X"
private const val RELATIVE_LIBRARY = "Claude-3p/configLibrary"
