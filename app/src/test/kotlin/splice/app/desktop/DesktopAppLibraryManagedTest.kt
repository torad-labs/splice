// Verifies DesktopAppLibrary managed-configuration existence checks across multi-path platform lists (e.g. macOS MDM
// plists) when DesktopManagedPolicy.redirected is null, as well as the test seam override.
package splice.app.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.desktop.setup.DesktopAppLibrary
import splice.app.desktop.setup.DesktopManagedPolicy
import splice.client.desktop.DesktopAppConfig
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DesktopAppLibraryManagedTest {
    @Test
    fun `managed and refusal report first existing platform managed path when redirected is null`(
        @TempDir tmp: Path,
    ) {
        val env = EnvReader { key -> if (key == "HOME") tmp.resolve("home").toString() else null }
        val config = DesktopAppConfig(env, osName = "Mac OS X")
        Files.createDirectories(config.library)
        val machinePlist = tmp.resolve("managed/com.anthropic.claudefordesktop.plist")
        val userPlist = tmp.resolve("managed/alice/com.anthropic.claudefordesktop.plist")
        val desktop = DesktopAppLibrary(config, managedPaths = listOf(machinePlist, userPlist))

        assertNull(DesktopManagedPolicy.redirected)
        assertFalse(desktop.managed())
        assertNull(desktop.refusal())

        Files.createDirectories(userPlist.parent)
        Files.writeString(userPlist, "<plist/>")
        assertTrue(desktop.managed())
        val userRefusal = desktop.refusal().orEmpty()
        assertTrue(userRefusal.startsWith("$userPlist "), userRefusal)

        Files.writeString(machinePlist, "<plist/>")
        assertTrue(desktop.managed())
        val machineRefusal = desktop.refusal().orEmpty()
        assertTrue(machineRefusal.startsWith("$machinePlist "), machineRefusal)
    }

    @Test
    fun `DesktopManagedPolicy within overrides platform managed paths for its block`(@TempDir tmp: Path) {
        val env = EnvReader { key -> if (key == "HOME") tmp.resolve("home").toString() else null }
        val config = DesktopAppConfig(env, osName = "Mac OS X")
        Files.createDirectories(config.library)
        val presentPlatformFile = tmp.resolve("managed/machine.plist")
        Files.createDirectories(presentPlatformFile.parent)
        Files.writeString(presentPlatformFile, "<plist/>")
        val desktop = DesktopAppLibrary(config, managedPaths = listOf(presentPlatformFile))

        val absentFixture = tmp.resolve("fixture/managed-settings.json")
        DesktopManagedPolicy.within(absentFixture) {
            assertFalse(desktop.managed())
            assertNull(desktop.refusal())
        }
        assertTrue(desktop.managed())
    }
}
