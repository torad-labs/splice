// `splice status` says a provider's reset in the machine's own zone, with that zone's abbreviation, and never names a zone
// the user is not in (the old text was hard-coded to America/Chicago). The rule is core's LocalTimeText, the one every product
// sentence that names a time uses; each case here names its zone, so none passes by the accident of the runner's.
package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepth
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.LocalTimeText
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

private val NOW_MS = Instant.parse("2026-09-29T00:00:00Z").toEpochMilli()
private val RESET_SECONDS = Instant.parse("2026-10-05T00:00:00Z").epochSecond

class ResetZoneStatusTest {
    private val topology = Topology(
        providers = mapOf(
            "muse" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://example.invalid",
                auth = AuthConfig(kind = "api-key", env = "TEST_MUSE_KEY"),
            ),
        ),
        heads = mapOf(
            "claude-muse" to HeadConfig(
                provider = "muse",
                port = 3103,
                discoveryPrefix = "muse--",
                pinnedModel = "m",
                claude = ClaudeWrapperConfig(command = "claude-muse"),
            ),
        ),
    )

    private fun limitedRow(bin: Path, times: LocalTimeText?): String {
        Files.createSymbolicLink(bin.resolve("claude-muse"), bin.resolve("target"))
        val vars = mapOf("SPLICE_BIN_DIR" to bin.toString(), "TEST_MUSE_KEY" to "synthetic-key")
        val palette = CliPalette(ColorDepth.NONE)
        val clock = WallClock { NOW_MS }
        val table = if (times == null) StatusTable(palette, clock) else StatusTable(palette, clock, times)
        val limited = mapOf("claude-muse" to RESET_SECONDS)
        return table.lines(topology, EnvReader(vars::get), StatusReadings(quotaResetAtEpochSeconds = limited))[1]
    }

    @Test
    fun `a reset is said in Tokyo's hour and abbreviation on a machine in Tokyo`(@TempDir bin: Path) {
        val row = limitedRow(bin, LocalTimeText(ZoneId.of("Asia/Tokyo")))

        assertTrue("out of quota until Oct 5, 9:00 AM JST" in row, row)
        assertFalse("CT" in row, "Chicago's label is not this machine's: $row")
    }

    @Test
    fun `a machine in Chicago still reads its own daylight abbreviation`(@TempDir bin: Path) {
        val row = limitedRow(bin, LocalTimeText(ZoneId.of("America/Chicago")))

        assertTrue("out of quota until Oct 4, 7:00 PM CDT" in row, row)
    }

    @Test
    fun `no zone given reads the machine's own, through the same rule`(@TempDir bin: Path) {
        val row = limitedRow(bin, null)

        assertTrue("out of quota until ${LocalTimeText().at(RESET_SECONDS)}" in row, row)
        assertEquals(1, Regex("out of quota until").findAll(row).count(), row)
    }
}
