// What ManagedSettings.reading says of a real managed folder, config folder and stand-in registry: the file is absent
// only where splice established that nothing is at its path, and every other place Claude Code may take managed
// settings from is named, as holding something or as one splice could not check, never dropped.
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
    fun `nothing at the path, with its folder listed, is absent and leaves no place unread`() {
        Files.createDirectories(platform)
        val read = reading()
        assertEquals(ManagedFile.Absent, read.state)
        assertTrue(read.unread.none, read.unread.toString())
        Files.delete(platform)
        assertEquals(ManagedFile.Absent, reading().state, "a missing folder under a listed one is absent too")
    }

    @Test
    fun `a link to nothing in place of the managed folder is not absent, and its drop-ins are unknown`() {
        Files.createSymbolicLink(platform, tmp.resolve("gone"))
        val read = reading()
        assertEquals(ManagedFile.Unreadable, read.state)
        assertEquals(listOf(platform.resolve("managed-settings.d").toString()), read.unread.unsure)
    }

    @Test
    fun `a link to nothing at the file is not absent`() {
        Files.createDirectories(platform)
        Files.createSymbolicLink(file, platform.resolve("gone.json"))
        assertEquals(ManagedFile.Unreadable, reading().state)
    }

    @Test
    fun `remote settings are looked for in CLAUDE_CONFIG_DIR when it is set, and in the home's folder otherwise`() {
        Files.createDirectories(platform)
        val tree = Files.createDirectories(tmp.resolve("desktop-tree"))
        val remote = Files.writeString(tree.resolve("remote-settings.json"), "{}")
        val moved = EnvReader { if (it == "CLAUDE_CONFIG_DIR") tree.toString() else env(it) }
        assertEquals(listOf(remote.toString()), reading(env = moved).unread.held)
        assertTrue(reading().unread.none, "nothing is in the home's own folder")
    }

    @Test
    fun `the per-user registry policy is named as a place, and a registry splice cannot query is unknown`() {
        Files.createDirectories(platform)
        val reg = standInReg()
        val value = "\r\nHKEY_CURRENT_USER\\x\r\n    Settings    REG_SZ    {}\r\n"
        Files.writeString(reg.resolveSibling("hkcu.out"), value)
        Files.writeString(reg.resolveSibling("parent.out"), "\r\nHKEY_LOCAL_MACHINE\\SOFTWARE\\Policies\\Other\r\n")
        val read = reading("Windows 11", PlatformPolicy("Windows 11", reg = reg, env = env))
        assertEquals(listOf("the registry value HKCU\\SOFTWARE\\Policies\\ClaudeCode\\Settings"), read.unread.held)
        assertTrue(read.unread.unsure.isEmpty(), "HKLM's parent lists no ClaudeCode key: ${read.unread}")
        val broken = PlatformPolicy("Windows 11", reg = tmp.resolve("no-reg.exe"), env = env)
        assertEquals(2, reading("Windows 11", broken).unread.unsure.size, "neither registry value could be checked")
    }

    /** A stand-in for reg.exe: `query <key> /v Settings` prints `hklm.out` or `hkcu.out` and exits 0 where the file is
     *  there, and exits 1 as reg.exe does for a value it can't find otherwise; `query <key>` with no value prints
     *  `parent.out` the same way, for any key. */
    private fun standInReg(): Path {
        val dir = Files.createDirectories(tmp.resolve("reg"))
        val script = dir.resolve("reg")
        Files.writeString(
            script,
            """
            #!/bin/sh
            here=${'$'}(dirname "${'$'}0")
            if [ "${'$'}3" = "/v" ]; then
              case "${'$'}2" in HKLM*) out=hklm.out ;; *) out=hkcu.out ;; esac
            else
              case "${'$'}2" in *ClaudeCode) exit 1 ;; *) out=parent.out ;; esac
            fi
            [ -f "${'$'}here/${'$'}out" ] || exit 1
            cat "${'$'}here/${'$'}out"
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        return script
    }
}
