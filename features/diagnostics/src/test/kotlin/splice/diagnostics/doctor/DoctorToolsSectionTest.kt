// The doctor's "tools" section: the optional programs splice works beside, as the executable reads them. A machine that
// uses none of them has no such section, and nothing a tool says can turn the report red: WARN is the most it earns.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

class DoctorToolsSectionTest {

    @Test
    fun `each reading is a row in the tools section, at its own level`(@TempDir tmp: Path) {
        val tools = ToolsRead { _, _ ->
            listOf(
                ToolReading("claude-desktop", ToolLevel.WARN, "not installed", "install Claude Desktop"),
                ToolReading("agy", ToolLevel.INFO, "found"),
                ToolReading("systemd", ToolLevel.OK, "found"),
            )
        }
        val section = sections(tmp, tools).getValue(TOOLS_SECTION)
        assertEquals(
            listOf(CheckStatus.WARN, CheckStatus.INFO, CheckStatus.OK),
            section.map { it.status },
            section.toString(),
        )
        assertEquals("install Claude Desktop", section.first().fix)
        assertFalse(section.any { it.status == CheckStatus.FAIL }, "a tool never fails the report")
    }

    @Test
    fun `a machine with no tool to say has no tools section at all`(@TempDir tmp: Path) {
        assertFalse(TOOLS_SECTION in sections(tmp, ToolsRead { _, _ -> emptyList() }))
    }

    @Test
    fun `a reader that throws is one crashed row, not a dead report`(@TempDir tmp: Path) {
        val sections = sections(tmp, ToolsRead { _, _ -> error("boom") })
        val row = sections.getValue(TOOLS_SECTION).single()
        assertTrue("check crashed" in row.detail, row.detail)
        assertTrue("installation" in sections, "the other sections still ran")
    }

    private fun sections(tmp: Path, tools: ToolsRead): Map<String, List<DoctorCheck>> {
        val env = mapOf(
            "XDG_CONFIG_HOME" to Files.createDirectories(tmp.resolve("config")).toString(),
            "SPLICE_BIN_DIR" to Files.createDirectories(tmp.resolve("bin")).toString(),
            "SPLICE_SHARE_DIR" to Files.createDirectories(tmp.resolve("share")).toString(),
            "PATH" to tmp.resolve("bin").toString(),
            "SPLICE_STATE_DIR" to Files.createDirectories(tmp.resolve("state")).toString(),
            "SPLICE_CONTROL_PORT" to TestPorts.reserve().toString(),
        )
        val quiet = TerminalOutput { }
        val doctor = DoctorCommand(quiet, quiet, DoctorTestPorts.noJar, DoctorTestPorts.silentLocal, tools = tools)
        return doctor.collect(EnvReader { env[it] }).sections.toMap()
    }
}
