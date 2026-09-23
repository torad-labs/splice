// Split from app's PerfCommandTest when PerfSummary went internal to features/usage (LAYOUT-01):
// `splice perf` prints the numbers /api/perf/summary serves for the same rows, because both go through
// PerfSummary.summarize. App's arm keeps the wiring to each head's perf file; this one keeps the
// equality, where the summary is visible.
package splice.usage.perf

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.nio.file.Path

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

class PerfCommandSummaryTest {

    @Test
    fun `the CLI prints the API summary's p50 for the chosen window`(@TempDir tmp: Path) {
        val config = tmp.resolve("splice.toml")
        TopologyLoader.loadOrMaterialize(config)
        val env = EnvReader { name -> if (name == "SPLICE_CONFIG") config.toString() else null }
        val now = System.currentTimeMillis()
        val rows = listOf(800L to 3 * DAY_MS, 400L to 2 * HOUR_MS, 300L to HOUR_MS / 2, 200L to HOUR_MS / 3)
            .map { (total, age) -> PerfRow(now - age, OutcomeTag.OK.wire, mapOf(PerfKeys.TOTAL to total)) }
        val source = PerfRowsSource { since -> PerfRowsWindow(rows.filter { it.ts >= since }) }
        val api = PerfSummary().summarize(source, PerfWindow.D7)
        val p50 = api.getValue("total_ms").jsonObject.getValue("p50").jsonPrimitive.content

        val printed = mutableListOf<String>()
        val terminal = TerminalOutput { printed += it }
        val ok = PerfCommand(terminal, terminal, HeadPerfRows { _, _ -> source }).perf(listOf("--window", "7d"), env)

        assertTrue(ok, printed.joinToString("\n"))
        assertTrue(printed.any { "p50 $p50 ms" in it }, "the CLI shows the API's p50: $printed")
    }
}
