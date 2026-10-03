// V4-218: a pasted PATH fix must name the same home that InstallPaths resolved, even when HOME is
// unset or blank and the resolver fell back to the JVM's user.home.
package splice.diagnostics.doctor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.wrap.WrappedHead
import splice.core.config.RunningJar
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.diagnostics.doctor.report.DoctorRedaction
import java.nio.file.Files
import java.nio.file.Path

class DoctorPathCheckTest {

    @Test
    fun `the console checks the user shell path while CLI doctor keeps its caller path`(@TempDir home: Path) {
        val bin = Files.createDirectories(home.resolve(".local/bin"))
        val claude = Files.writeString(bin.resolve("claude"), "#!/bin/sh\nprintf '9.9.9\\n'\n")
        assertTrue(claude.toFile().setExecutable(true))
        val shell = Files.writeString(
            home.resolve("synthetic-shell"),
            "#!/bin/sh\n[ \"\$1\" = \"-lic\" ] || exit 2\n" +
                "PATH=\"$bin\"; export PATH\nexec /bin/sh -c \"\$2\"\n",
        )
        assertTrue(shell.toFile().setExecutable(true))
        val values = mapOf(
            "HOME" to home.toString(),
            "PATH" to home.resolve("daemon-bin").toString(),
            "SHELL" to shell.toString(),
        )
        val env = EnvReader { values[it] }
        val answers = DaemonAnswers(
            health = """{"version":"0.4.0","heads":0,"readyHeads":0,"failedHeads":0}""",
            heads = """{"heads":[]}""",
            auth = "{}",
            trace = emptyMap(),
            unmappedTiers = emptyMap(),
        )
        UserHome.within(home) {
            val doctor = DoctorTestPorts.doctor()
            val report = doctor.reportJson(env, answers)
            val checks = Json.parseToJsonElement(report).jsonObject.getValue("checks").jsonArray.map { it.jsonObject }
            for (id in listOf("prerequisites/claude", "installation/PATH")) {
                val row = checks.single { it.getValue("id").jsonPrimitive.content == id }
                assertEquals("ok", row.getValue("status").jsonPrimitive.content, row.toString())
            }
            val caller = DoctorPathCheck(DoctorProbes(RunningJar { null }, WrappedHead(home)))
            assertEquals(
                CheckStatus.FAIL,
                caller.check(bin, env).status,
                "a shell CLI still measures its own supplied PATH",
            )
        }
    }

    @Test
    fun `an unreadable shell path stays unknown without erasing installation checks or exposing startup output`(
        @TempDir home: Path,
    ) {
        val shell = Files.writeString(
            home.resolve("failed-shell"),
            "#!/bin/sh\nprintf 'synthetic startup noise'\nexit 7\n",
        )
        assertTrue(shell.toFile().setExecutable(true))
        val values = mapOf("HOME" to home.toString(), "SHELL" to shell.toString(), "PATH" to "/daemon-only")
        val env = EnvReader { values[it] }
        val answers = DaemonAnswers(
            health = """{"version":"0.4.0","heads":0,"readyHeads":0,"failedHeads":0}""",
            heads = """{"heads":[]}""",
            auth = "{}",
            trace = emptyMap(),
            unmappedTiers = emptyMap(),
        )
        UserHome.within(home) {
            val report = DoctorTestPorts.doctor().reportJson(env, answers)
            val checks = Json.parseToJsonElement(report).jsonObject.getValue("checks").jsonArray.map { it.jsonObject }
            for (id in listOf("prerequisites/claude", "installation/PATH")) {
                val row = checks.single { it.getValue("id").jsonPrimitive.content == id }
                assertEquals("warn", row.getValue("status").jsonPrimitive.content, row.toString())
                assertTrue(row.getValue("detail").jsonPrimitive.content.contains("not checked"))
            }
            assertTrue(checks.any { it.getValue("id").jsonPrimitive.content == "installation/shim" })
            assertTrue(!report.contains("synthetic startup noise"), report)
            assertTrue(!report.contains("Claude Code not found"), report)
        }
    }

    @Test
    fun `a stuck shell probe is bounded and stopped instead of reporting missing binaries`(@TempDir home: Path) {
        val pidFile = home.resolve("shell.pid")
        val shell = Files.writeString(
            home.resolve("stuck-shell"),
            "#!/bin/sh\nprintf '%s' \"\$\$\" > '$pidFile'\nwhile :; do /bin/sleep 1; done\n",
        )
        assertTrue(shell.toFile().setExecutable(true))
        val values = mapOf("HOME" to home.toString(), "SHELL" to shell.toString())
        val started = System.nanoTime()
        val captured = DoctorShellPath().environment(splice.core.util.EnvReader { values[it] })
        org.junit.jupiter.api.assertThrows<java.io.IOException> { captured("PATH") }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 10_000, "the shell probe escaped its bound: $elapsedMs ms")
        val pid = Files.readString(pidFile).toLong()
        assertTrue(!ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the probe shell must be stopped")
    }

    @Test
    fun `the PATH remedy uses an absolute fallback when HOME is blank`(@TempDir tmp: Path) {
        val home = tmp.resolve("passwd-home")
        val bin = home.resolve(".local/bin")
        val pathCheck = DoctorPathCheck(DoctorProbes(RunningJar { null }, WrappedHead(home)))
        UserHome.within(home) {
            for (value in listOf(null, "", "  ")) {
                val env = EnvReader { name -> if (name == "HOME") value else null }
                val fix = pathCheck.check(bin, env).fix
                val expected = "add to your shell rc: export PATH=\"" +
                    "\$(node -p 'require(\"node:os\").userInfo().homedir')/.local/bin:\$PATH\""
                assertEquals(expected, fix, "HOME=$value must not appear in the pasted command")
                assertEquals(expected, DoctorRedaction(home).text(checkNotNull(fix)))
            }
            val explicit = EnvReader { name -> if (name == "HOME") home.toString() else null }
            assertEquals(
                "add to your shell rc: export PATH=\"\$HOME/.local/bin:\$PATH\"",
                pathCheck.check(bin, explicit).fix,
            )
        }
    }
}
