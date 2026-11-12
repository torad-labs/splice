// `splice doctor --json` run as the installed command runs it: a child JVM on the app's main class, with HOME naming
// a fresh directory and no splice selector in its environment. The exit code, the printed JSON and the --out file are
// what a user pastes into an issue, so they are read from the process, not from the command body. A child JVM because
// HOME is the process's environment, which no in-process test can give a different value.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

private val SELECTOR_PREFIXES = listOf("SPLICE_", "CLAUDEX_", "XDG_", "CONTROL_")
private const val CHILD_SECONDS = 120L

class DoctorProcessTest {

    private data class Ran(val exit: Int, val stdout: String)

    private fun doctor(tmp: Path, vararg args: String): Ran {
        val home = Files.createDirectories(tmp.resolve("home"))
        val output = tmp.resolve("doctor.out")
        val builder = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-Duser.home=${Files.createDirectories(tmp.resolve("passwd-home"))}",
            "-cp",
            System.getProperty("java.class.path"),
            "splice.app.MainKt",
            "doctor",
            *args,
        ).directory(tmp.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile())
        builder.environment().apply {
            keys.removeIf { key -> SELECTOR_PREFIXES.any(key::startsWith) }
            put("HOME", home.toString())
            // A port nothing listens on, so doctor finds no daemon to read.
            put("SPLICE_CONTROL_PORT", TestPorts.reserve().toString())
        }
        val process = builder.start()
        try {
            assertTrue(process.waitFor(CHILD_SECONDS, TimeUnit.SECONDS), "splice doctor ran past $CHILD_SECONDS s")
        } finally {
            process.destroyForcibly()
        }
        return Ran(process.exitValue(), Files.readString(output))
    }

    private fun failed(report: JsonObject): Boolean =
        report.getValue("checks").jsonArray.any { it.jsonObject.getValue("status").jsonPrimitive.content == "fail" }

    @Test
    fun `doctor --json prints one report and exits non-zero exactly when a check failed`(@TempDir tmp: Path) {
        val ran = doctor(tmp, "--json")

        val report = Json.parseToJsonElement(ran.stdout).jsonObject
        assertEquals(1, report.getValue("schema_version").jsonPrimitive.content.toInt())
        assertTrue(report.getValue("checks").jsonArray.isNotEmpty(), ran.stdout)
        assertEquals(failed(report), ran.exit != 0, "exit ${ran.exit} against the printed checks")
        assertFalse(ran.stdout.contains(tmp.resolve("home").toString()), "the home path is shown as ~")
    }

    @Test
    fun `doctor --json --out writes the same report to a file only its owner can read`(@TempDir tmp: Path) {
        val file = tmp.resolve("report.json")

        val ran = doctor(tmp, "--json", "--out", file.toString())

        assertTrue(ran.stdout.contains("doctor report written to"), ran.stdout)
        val report = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(1, report.getValue("schema_version").jsonPrimitive.content.toInt())
        assertEquals(failed(report), ran.exit != 0)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }
}
