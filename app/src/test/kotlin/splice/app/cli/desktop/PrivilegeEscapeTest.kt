// desktop-antigravity: a launch carrying Linux's no-new-privileges bit (Claude Desktop after an in-app restart) runs
// itself again as a transient user service, with every variable passed by name and no value on the command line;
// any other launch runs where it is.
package splice.app.cli.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class PrivilegeEscapeTest(@param:TempDir private val tmp: Path) {
    private val bin: Path = Files.createDirectories(tmp.resolve("bin"))
    private val run: Path = Files.writeString(bin.resolve("systemd-run"), "#!/bin/sh\n")
        .also { check(it.toFile().setExecutable(true)) }
    private val self = listOf("/usr/bin/java", "-jar", "/opt/splice.jar", "desktop", "launch", "--state-dir", "/s")

    private fun status(noNewPrivs: Int): Path =
        Files.writeString(tmp.resolve("status-$noNewPrivs"), "Name:\tjava\nNoNewPrivs:\t$noNewPrivs\nSeccomp:\t0\n")

    private fun env(extra: Map<String, String> = emptyMap()): EnvReader {
        val vars = mapOf("PATH" to bin.toString(), "XDG_RUNTIME_DIR" to tmp.toString()) + extra
        return EnvReader { vars[it] }
    }

    @Test
    fun `under the bit the launch reruns as a user service, passing variables by name only`() {
        val names = setOf("HOME", "ANTHROPIC_BASE_URL", "AWS_BEARER_TOKEN_BEDROCK", "INVOCATION_ID")
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
    fun `without the bit, inside an escape, off Linux or with no service manager, it runs where it is`() {
        val names = setOf("HOME")
        assertNull(PrivilegeEscape(env(), status(0), linux = true).command(self, names, "u"))
        assertNull(PrivilegeEscape(env(), tmp.resolve("no-status"), linux = true).command(self, names, "u"))
        assertNull(PrivilegeEscape(env(), status(1), linux = false).command(self, names, "u"))
        val inside = env(mapOf("SPLICE_DESKTOP_ESCAPED" to "1"))
        assertNull(PrivilegeEscape(inside, status(1), linux = true).command(self, names, "u"))
        val noManager = EnvReader { if (it == "PATH") bin.toString() else null }
        assertNull(PrivilegeEscape(noManager, status(1), linux = true).command(self, names, "u"))
        val noSystemdRun = EnvReader { if (it == "XDG_RUNTIME_DIR") tmp.toString() else null }
        assertNull(PrivilegeEscape(noSystemdRun, status(1), linux = true).command(self, names, "u"))
        assertTrue(PrivilegeEscape(env(), status(1), linux = true).command(self, names, "u") != null)
    }
}
