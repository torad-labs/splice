// `splice perf [--window]` (v0.4.0, FEATURES.md §3) against a temp state dir: the CLI prints the
// numbers /api/perf/summary serves for the same files (both go through PerfSummary.summarize over
// PerfRowsFileSource), --window selects the window, and an unknown window is refused.
package splice.app.cli.status

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.daemon.TopologyLoader
import splice.app.sources.PerfRowsFileSource
import splice.control.api.usage.PerfSummary
import splice.control.api.usage.PerfWindow
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

class PerfCommandTest {

    /** The starter topology is written by the TEST: `splice perf` itself is read-only. */
    private fun env(tmp: Path): EnvReader {
        TopologyLoader.loadOrMaterialize(tmp.resolve("splice.toml"))
        return EnvReader { name ->
            when (name) {
                "SPLICE_CONFIG" -> tmp.resolve("splice.toml").toString()
                "CLAUDEX_STATE_DIR" -> tmp.resolve("state").toString()
                else -> null
            }
        }
    }

    private fun perfRow(ts: Long, outcome: String, total: Long) =
        """{"ts":$ts,"model":"m","outcome":"$outcome","first_byte":${total / 2},"stream_end":$total,"total":$total}"""

    private fun capture(block: () -> Boolean): Pair<Boolean, String> {
        val out = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(out, true))
        val ok = try {
            block()
        } finally {
            System.setOut(prev)
        }
        return ok to out.toString()
    }

    @Test
    fun `the CLI prints the API summary's numbers for the chosen window`(@TempDir tmp: Path) {
        val env = env(tmp)
        val file = StatePaths(envReader = env).perfStatsFile("openrouter")
        Files.createDirectories(file.parent)
        val now = System.currentTimeMillis()
        Files.writeString(
            file,
            listOf(
                perfRow(now - 3 * DAY_MS, "ok", 800L),
                perfRow(now - 2 * HOUR_MS, "ok", 400L),
                perfRow(now - HOUR_MS / 2, "error:upstream-failed", 300L),
                perfRow(now - HOUR_MS / 3, "ok", 200L),
            ).joinToString("\n", postfix = "\n"),
        )
        val api = PerfSummary().summarize(PerfRowsFileSource(file), PerfWindow.D7)
        val p50 = api.getValue("total_ms").jsonObject.getValue("p50").jsonPrimitive.content

        val (ok, text) = capture { PerfCommand().perf(listOf("--window", "7d"), env) }

        assertTrue(ok)
        assertTrue(text.contains("last 7d per head"), text)
        assertTrue(text.contains("4 turn(s)"), text)
        assertTrue(text.contains("p50 $p50 ms"), "the CLI shows the API's p50: $text")
        assertTrue(text.contains("max ") && text.contains("(n=4)"), "max and the denominator: $text")
        assertTrue(text.contains("error:upstream-failed=1 (25.0%)"), "failure share per outcome tag: $text")
        assertTrue(text.contains("failure share 25.0%"), text)

        val (dayOk, day) = capture { PerfCommand().perf(listOf("--window", "1h"), env) }
        assertTrue(dayOk)
        assertTrue(day.contains("2 turn(s)"), "the 1h window holds two rows: $day")
        assertFalse(day.contains("clamped"), day)
    }

    @Test
    fun `an unknown window, a bare flag and a stray argument are refused and the default is 24h`(@TempDir tmp: Path) {
        val env = env(tmp)
        assertFalse(capture { PerfCommand().perf(listOf("--window", "2h"), env) }.first)
        assertFalse(capture { PerfCommand().perf(listOf("--window"), env) }.first, "a missing value is not 24h")
        assertFalse(capture { PerfCommand().perf(listOf("--window", "7d", "extra"), env) }.first)
        assertFalse(capture { PerfCommand().perf(listOf("7d"), env) }.first, "the label needs its flag")
        val (ok, text) = capture { PerfCommand().perf(emptyList(), env) }
        assertTrue(ok)
        assertTrue(text.contains("last 24h per head"), text)
        assertTrue(text.contains("no rows in this window"), text)
        assertTrue(text.contains("no perf rows recorded yet"), "nothing recorded is not a clamp: $text")
    }

    @Test
    fun `a read-only command never writes a topology and says when there is none`(@TempDir tmp: Path) {
        val bare = EnvReader { name ->
            when (name) {
                "SPLICE_CONFIG" -> tmp.resolve("splice.toml").toString()
                "CLAUDEX_STATE_DIR" -> tmp.resolve("state").toString()
                else -> null
            }
        }
        assertFalse(capture { PerfCommand().perf(emptyList(), bare) }.first)
        assertFalse(Files.exists(tmp.resolve("splice.toml")), "a diagnostic materializes nothing")
    }
}
