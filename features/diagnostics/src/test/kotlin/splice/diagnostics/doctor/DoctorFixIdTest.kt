// NEW: V4-220 item 4 — what the report tells the console about each fix: which rows the daemon can
// fix itself (`fix_id`), and that the fixes meant for pasting or following survive the report's redaction.
// Marlin's review of the doctor-1600x1000 capture: the Fix column read
// `add to your shell rc: export PATH="<redacted:path>"`, a command that cannot be pasted.
package splice.diagnostics.doctor

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.diagnostics.doctor.report.DoctorJsonReport
import splice.diagnostics.doctor.report.DoctorRun
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DoctorFixIdTest {

    @TempDir
    lateinit var tmp: Path

    private val home: Path = Paths.get(System.getProperty("user.home"))

    /** RED before item 4: the line was built from the expanded bin dir, and the redaction masked it. */
    @Test
    fun `the PATH fix survives the report's redaction and pastes as written`() {
        val underHome = home.resolve(".local").resolve("bin")
        assertTrue(
            pathFixDetail(underHome, env = emptyMap())
                .endsWith("fix: add to your shell rc: export PATH=\"\$HOME/.local/bin:\$PATH\""),
            pathFixDetail(underHome, env = emptyMap()),
        )
        // A bin dir outside home (SPLICE_BIN_DIR) is splice's own directory to the redaction, but
        // only when the quote ends the path: `/opt/splice/bin:$PATH` was one foreign token.
        val outside = Paths.get("/opt/splice/bin")
        val detail = pathFixDetail(outside, env = mapOf("SPLICE_BIN_DIR" to outside.toString()))
        assertTrue(detail.endsWith("fix: add to your shell rc: export PATH=\"/opt/splice/bin\":\"\$PATH\""), detail)
    }

    /** RED before item 4: `install it: https:<redacted:path>`. Every prerequisite's fix, taken from the
     *  table itself, so a link added there without the redaction allowing it fails by name. */
    @Test
    fun `every prerequisite fix reaches the report as written, links included`() {
        val rows = binaries.map { DoctorCheck(it.name, CheckStatus.FAIL, it.missingDetail, it.fix) }
        val details = checks(rows, env = emptyMap()).map { it.getValue("detail").jsonPrimitive.content }
        binaries.zip(details).forEach { (spec, detail) ->
            assertTrue(detail.endsWith("fix: ${spec.fix}"), "${spec.name}: $detail")
        }
    }

    @Test
    fun `a wrapper install --all can relink carries fix_id install_all, and no operator-only row does`() {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val shim = Files.writeString(tmp.resolve("splice-launch"), "#!/bin/sh\n")
        Files.createSymbolicLink(bin.resolve("claude-gone"), tmp.resolve("nowhere"))
        Files.createSymbolicLink(bin.resolve("claude-ok"), shim)
        Files.writeString(bin.resolve("claude-foreign"), "#!/bin/sh\n")
        val path = DoctorPathCheck(DoctorTestPorts.probes())
        val rows = listOf("claude-missing", "claude-gone", "claude-ok", "claude-foreign")
            .map { path.wrapperCheck(bin.resolve(it), it) } +
            path.check(bin) { null }
        val ids = checks(rows, emptyMap()).map { it.getValue("fix_id") }
        assertEquals(
            listOf("install_all", "install_all", null, null, null),
            ids.map { if (it == JsonNull) null else it.jsonPrimitive.content },
            "not linked and dangling are install --all's; linked, a foreign file and PATH are not: $ids",
        )
    }

    private fun pathFixDetail(bin: Path, env: Map<String, String>): String {
        val onlyUsrBin = EnvReader { name -> if (name == "PATH") "/usr/bin" else null }
        val row = DoctorPathCheck(DoctorTestPorts.probes()).check(bin, onlyUsrBin)
        return checks(listOf(row), env).single().getValue("detail").jsonPrimitive.content
    }

    private fun checks(rows: List<DoctorCheck>, env: Map<String, String>): List<JsonObject> {
        val state = Files.createDirectories(tmp.resolve("state")).toString()
        val reader = EnvReader { name -> (env + ("CLAUDEX_STATE_DIR" to state))[name] }
        val report = DoctorJsonReport(
            envReader = reader,
            claudeVersion = { "x" },
            home = home,
            statePaths = StatePaths(envReader = reader),
        )
        return report
            .build(DoctorRun(null, listOf("installation" to rows)), withLogs = false)
            .getValue("checks").jsonArray.map { it.jsonObject }
    }
}
