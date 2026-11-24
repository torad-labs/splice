// desktop-antigravity: a launch carrying Linux's no-new-privileges bit (Claude Desktop after an in-app restart) runs
// itself again as a transient user service, with every variable passed by name and no value on the command line;
// any other launch, and one with no user service manager to reach, runs where it is.
package splice.app.cli.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class PrivilegeEscapeTest(@param:TempDir private val tmp: Path) {
    private val bin: Path = Files.createDirectories(tmp.resolve("bin"))
    private val run: Path = Files.writeString(bin.resolve("systemd-run"), "#!/bin/sh\n")
        .also { check(it.toFile().setExecutable(true)) }
    private val runtime: Path = Files.createDirectories(tmp.resolve("runtime"))
    private val self = listOf("/usr/bin/java", "-jar", "/opt/splice.jar", "desktop", "launch", "--state-dir", "/s")

    init {
        Files.writeString(runtime.resolve("bus"), "")
    }

    private fun status(noNewPrivs: Int): Path =
        Files.writeString(tmp.resolve("status-$noNewPrivs"), "Name:\tjava\nNoNewPrivs:\t$noNewPrivs\nSeccomp:\t0\n")

    private fun env(vararg extra: Pair<String, String>): EnvReader {
        val vars = mapOf("PATH" to bin.toString(), "XDG_RUNTIME_DIR" to runtime.toString()) + extra
        return EnvReader { vars[it] }
    }

    @Test
    fun `under the bit the launch reruns as a user service, passing shell-named variables by name only`() {
        val names = setOf("HOME", "ANTHROPIC_BASE_URL", "AWS_BEARER_TOKEN_BEDROCK", "INVOCATION_ID", "BASH_FUNC_ll%%")
        val command = PrivilegeEscape(env(), status(1), linux = true).command(self, names, "splice-desktop-launch-7")
        val expected = listOf(
            run.toString(), "--user", "--pipe", "--wait", "--quiet", "--collect", "--same-dir",
            "--unit=splice-desktop-launch-7",
            "-E", "ANTHROPIC_BASE_URL", "-E", "AWS_BEARER_TOKEN_BEDROCK", "-E", "HOME", "-E", "SPLICE_DESKTOP_ESCAPED",
            "--",
        ) + self
        assertEquals(expected, command)
        val passed = command.orEmpty().zipWithNext().filter { it.first == "-E" }.map { it.second }
        assertFalse(passed.any { '=' in it }, "no value rides on the command line")
    }

    @Test
    fun `a session bus address, or the manager's private socket, is a manager to reach`() {
        Files.delete(runtime.resolve("bus"))
        val bare = PrivilegeEscape(env(), status(1), linux = true)
        assertNull(bare.command(self, setOf("HOME"), "u"), "no bus and no socket: the session runs here")
        val dbus = PrivilegeEscape(env("DBUS_SESSION_BUS_ADDRESS" to "unix:path=/x"), status(1), linux = true)
        assertNotNull(dbus.command(self, setOf("HOME"), "u"))
        Files.writeString(Files.createDirectories(runtime.resolve("systemd")).resolve("private"), "")
        assertNotNull(bare.command(self, setOf("HOME"), "u"))
    }

    @Test
    fun `without the bit, inside an escape, off Linux or with no systemd-run, it runs where it is`() {
        val names = setOf("HOME")
        assertNull(PrivilegeEscape(env(), status(0), linux = true).command(self, names, "u"))
        assertNull(PrivilegeEscape(env(), tmp.resolve("no-status"), linux = true).command(self, names, "u"))
        assertNull(PrivilegeEscape(env(), status(1), linux = false).command(self, names, "u"))
        val inside = env("SPLICE_DESKTOP_ESCAPED" to "1")
        assertNull(PrivilegeEscape(inside, status(1), linux = true).command(self, names, "u"))
        val noRuntime = EnvReader { if (it == "PATH") bin.toString() else null }
        assertNull(PrivilegeEscape(noRuntime, status(1), linux = true).command(self, names, "u"))
        val noSystemdRun = EnvReader { if (it == "XDG_RUNTIME_DIR") runtime.toString() else null }
        assertNull(PrivilegeEscape(noSystemdRun, status(1), linux = true).command(self, names, "u"))
    }

    @Test
    fun `a folder named like the program on PATH is not the program`() {
        val other = Files.createDirectories(tmp.resolve("other"))
        Files.createDirectories(other.resolve("systemd-run"))
        val folderOnly = EnvReader { mapOf("PATH" to other.toString(), "XDG_RUNTIME_DIR" to runtime.toString())[it] }
        assertNull(PrivilegeEscape(folderOnly, status(1), linux = true).program("systemd-run"))
        assertNull(PrivilegeEscape(folderOnly, status(1), linux = true).command(self, setOf("HOME"), "u"))
    }
}
