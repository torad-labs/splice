// What ManagedSettings.reading gives the text that speaks of Claude Code's managed settings, from a real managed
// folder: the file is absent only where splice established that nothing is at its path, and the places Claude Code may
// also take managed settings from are named on every run, without splice querying the stores or reading them.
package splice.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class ManagedReadingTest(@param:TempDir private val tmp: Path) {
    private val home = Files.createDirectories(tmp.resolve("home"))
    private val platform = tmp.resolve("platform")
    private val file = platform.resolve("managed-settings.json")
    private val env = EnvReader { if (it == "HOME") home.toString() else null }

    private fun reading(os: String = "Linux", policy: PlatformPolicy = PlatformPolicy(os), env: EnvReader = this.env) =
        UserHome.within(home) { ManagedSettings(os, platformDir = platform, policy = policy).reading(env) }

    @Test
    fun `nothing at the path is absent, and a link to nothing in place of the file or its folder is not`() {
        Files.createDirectories(platform)
        assertEquals(ManagedFile.Absent, reading().state)
        Files.createSymbolicLink(file, platform.resolve("gone.json"))
        assertEquals(ManagedFile.Unreadable, reading().state)
        Files.delete(file)
        Files.delete(platform)
        Files.createSymbolicLink(platform, tmp.resolve("gone"))
        assertEquals(ManagedFile.Unreadable, reading().state)
        assertTrue("${platform.resolve("managed-settings.d")}" in reading().unread, "its drop-ins are not known")
    }

    @Test
    fun `the remote settings are named in the config folder this process sees, whether or not anything is there`() {
        Files.createDirectories(platform)
        val tree = tmp.resolve("desktop-tree")
        val moved = EnvReader { if (it == "CLAUDE_CONFIG_DIR") tree.toString() else env(it) }
        val named = "remote-settings.json in Claude Code's config folder"
        assertTrue(reading().unread.single { named in it }.contains("(${home.resolve(".claude")}, unless"))
        assertTrue(reading(env = moved).unread.single { named in it }.contains("($tree, unless"))
    }

    @Test
    fun `the system's policy stores are named without being queried`() {
        Files.createDirectories(platform)
        val ran = tmp.resolve("ran")
        val reg = Files.writeString(tmp.resolve("reg"), "#!/bin/sh\ntouch '$ran'\nexit 1\n")
        reg.toFile().setExecutable(true)
        val windows = reading("Windows 11", PlatformPolicy("Windows 11", reg = reg, env = env)).unread
        for (hive in listOf("HKLM", "HKCU")) {
            assertTrue("the registry value $hive\\SOFTWARE\\Policies\\ClaudeCode\\Settings" in windows, "$windows")
        }
        assertTrue(Files.notExists(ran), "reg.exe was not run")
        val mac = reading("Mac OS X", PlatformPolicy("Mac OS X", preferences = tmp.resolve("prefs"), user = "alex"))
        assertTrue("${tmp.resolve("prefs/alex/com.anthropic.claudecode.plist")}" in mac.unread, "${mac.unread}")
        assertTrue("${tmp.resolve("prefs/com.anthropic.claudecode.plist")}" in mac.unread, "${mac.unread}")
    }

    @Test
    fun `each drop-in listed is named, and a folder splice cannot list is named in their place`() {
        val dropIns = Files.createDirectories(platform.resolve("managed-settings.d"))
        val dropIn = Files.writeString(dropIns.resolve("10-org.json"), "{}")
        Files.writeString(dropIns.resolve(".hidden.json"), "{}")
        assertTrue("$dropIn" in reading().unread && reading().unread.none { "hidden" in it }, "${reading().unread}")
    }
}
