// #525 item 3: `splice upgrade` re-lays `splice.service` from the newly activated release jar so unit updates
// (such as ExecReload) take effect without a separate `splice install`, while respecting user overrides.
package splice.lifecycle.upgrade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.lifecycle.units.DAEMON_UNIT_NAME
import splice.lifecycle.units.SpliceUnits
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

internal class UpgradeDaemonUnitTest {
    @Test
    fun `refresh lays splice service from the activated jar and leaves user overrides intact`(@TempDir tmp: Path) {
        val unitDir = tmp.resolve("data/systemd/user")
        val overrideDir = tmp.resolve("config/systemd/user")
        val legacyRecord = tmp.resolve("state/mcp-hub/units-written")
        val binDir = tmp.resolve("bin")
        val javaHome = tmp.resolve("jdk-21")
        Files.createDirectories(overrideDir)
        val lines = mutableListOf<String>()
        val output = TerminalOutput { lines += it }
        val units = SpliceUnits(unitDir, overrideDir, legacyRecord, output, command = listOf("true"))
        val env = EnvReader { name ->
            when (name) {
                "HOME" -> tmp.toString()
                "SPLICE_BIN_DIR" -> binDir.toString()
                else -> null
            }
        }

        val jar = writeJar(
            tmp.resolve("splice.jar"),
            """
            # splice-owned unit
            [Service]
            Environment=PATH=@JAVA_HOME@/bin:/usr/bin
            ExecStart=@SPLICE@ daemon
            ExecReload=/bin/kill -HUP ${'$'}MAINPID
            """.trimIndent() + "\n",
        )
        val userOverride = "[Service]\nExecStart=/custom/splice\n"
        Files.writeString(overrideDir.resolve(DAEMON_UNIT_NAME), userOverride)

        UpgradeDaemonUnit(env, output, units).refresh(jar, javaHome)

        val laid = Files.readString(unitDir.resolve(DAEMON_UNIT_NAME))
        assertTrue("ExecReload=/bin/kill -HUP \$MAINPID" in laid, laid)
        assertTrue("Environment=PATH=$javaHome/bin:/usr/bin" in laid, laid)
        assertTrue("ExecStart=${binDir.resolve("splice")} daemon" in laid, laid)
        assertEquals(userOverride, Files.readString(overrideDir.resolve(DAEMON_UNIT_NAME)))
        assertTrue(lines.any { "laid $DAEMON_UNIT_NAME" in it }, lines.toString())
    }

    @Test
    fun `refresh skips cleanly when the jar is not an archive carrying a unit template`(@TempDir tmp: Path) {
        val unitDir = tmp.resolve("data/systemd/user")
        val output = TerminalOutput {}
        val units = SpliceUnits(
            unitDir,
            tmp.resolve("config/systemd/user"),
            tmp.resolve("state/units-written"),
            output,
            command = listOf("true"),
        )
        val fakeJar = tmp.resolve("not-a-jar.jar").also { Files.writeString(it, "plain-text") }
        val env = EnvReader { if (it == "HOME") tmp.toString() else null }

        UpgradeDaemonUnit(env, output, units).refresh(fakeJar, tmp.resolve("jdk-21"))

        assertFalse(Files.exists(unitDir.resolve(DAEMON_UNIT_NAME)))
    }

    private fun writeJar(path: Path, template: String): Path {
        JarOutputStream(Files.newOutputStream(path)).use { out ->
            out.putNextEntry(JarEntry("splice/lifecycle/units/splice.service"))
            out.write(template.toByteArray())
            out.closeEntry()
        }
        return path
    }
}
