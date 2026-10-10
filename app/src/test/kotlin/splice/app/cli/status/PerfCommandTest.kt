// `splice perf [--window]` (v0.4.0, FEATURES.md §3) against a temp state dir: the CLI reads each
// head's perf file (the PerfRowsFileSource the daemon's /api/perf reads), --window selects the window,
// and an unknown window is refused. That the printed numbers ARE the API summary's is held where
// PerfSummary is visible, features/usage's PerfCommandSummaryTest (LAYOUT-01).
package splice.app.cli.status

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.PerfWiring
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

class PerfCommandTest {

    /** The configured head is explicit: a fresh starter selects no plan. `splice perf` only reads. */
    private fun env(tmp: Path): EnvReader {
        Files.writeString(
            tmp.resolve("splice.toml"),
            """
            [providers.openrouter]
            dialect = "openai-chat"
            base_url = "https://example.invalid/v1"
            auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }
            [[providers.openrouter.models]]
            id = "m"
            context_window = 200000
            [heads.openrouter]
            provider = "openrouter"
            port = 3101
            discovery_prefix = "claude-openrouter--"
            pinned_model = "m"
            """.trimIndent() + "\n",
        )
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
    fun `the CLI reads each head's perf file for the chosen window`(@TempDir tmp: Path) {
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
        val (ok, text) = capture { PerfWiring.command().perf(listOf("--window", "7d"), env) }

        assertTrue(ok)
        assertTrue(text.contains("last 7d per head"), text)
        assertTrue(text.contains("4 turn(s)"), text)
        assertTrue(text.contains("p50 "), "the per-head percentiles are printed: $text")
        assertTrue(text.contains("max ") && text.contains("(n=4)"), "max and the denominator: $text")
        assertTrue(text.contains("error:upstream-failed=1 (25.0%)"), "failure share per outcome tag: $text")
        assertTrue(text.contains("failure share 25.0%"), text)

        val (dayOk, day) = capture { PerfWiring.command().perf(listOf("--window", "1h"), env) }
        assertTrue(dayOk)
        assertTrue(day.contains("2 turn(s)"), "the 1h window holds two rows: $day")
        assertFalse(day.contains("clamped"), day)
    }

    @Test
    fun `the CLI names what slowed or failed the head's turns, by model and by kind, and what telemetry was lost`(
        @TempDir tmp: Path,
    ) {
        val env = env(tmp)
        writeMixedRows(StatePaths(envReader = env).perfStatsFile("openrouter"))

        val (ok, text) = capture { PerfWiring.command().perf(listOf("--window", "1h"), env) }

        assertTrue(ok)
        assertTrue(text.contains("3 turn(s), 1 local step(s)"), "the activity answer is not a turn: $text")
        assertTrue(text.contains("time before first byte") && text.contains("time streaming"), text)
        assertTrue(text.contains("retries / refreshes     3 / 1"), text)
        assertTrue(text.contains("cache hit ratio         30.0%"), "600 of 2000 input tokens: $text")
        assertTrue(text.contains("peak inflight           5"), text)
        assertTrue(text.contains("telemetry dropped       2 row(s)"), "two rows saw the counter rise: $text")
        assertTrue(text.contains("kinds                   turn=2 compaction=1 activity_query=1"), text)
        assertTrue(text.contains("model fast") && text.contains("1 turn(s), failure 0.0%"), text)
        assertTrue(text.contains("model slow") && text.contains("2 turn(s), failure 50.0%"), text)
    }

    private fun row(at: Long, vararg fields: Pair<String, Any>) = (listOf("ts" to at) + fields).joinToString(
        prefix = "{",
        postfix = "}",
    ) { (key, value) -> "\"$key\":" + if (value is String) "\"$value\"" else value }

    /** Two models over four rows: a fast turn, a slow turn, a failed compaction, and the head's own activity answer. */
    private fun writeMixedRows(file: Path) {
        Files.createDirectories(file.parent)
        val now = System.currentTimeMillis()
        Files.writeString(file, (okTurns(now) + otherRows(now)).joinToString("\n", postfix = "\n"))
    }

    private fun okTurns(now: Long): List<String> {
        val ok = arrayOf("outcome" to "ok", "compact" to false)
        return listOf(
            row(
                now - 50_000,
                "model" to "fast",
                *ok,
                "first_byte" to 100,
                "stream_end" to 300,
                "total" to 320,
                "retries" to 1,
                "refreshes" to 0,
                "in_tokens" to 1000,
                "cached_tokens" to 600,
                "inflight" to 2,
                "async_io_drops" to 0,
            ),
            row(
                now - 40_000,
                "model" to "slow",
                *ok,
                "first_byte" to 4000,
                "stream_end" to 9000,
                "total" to 9100,
                "retries" to 2,
                "refreshes" to 1,
                "in_tokens" to 1000,
                "cached_tokens" to 0,
                "inflight" to 5,
                "async_io_drops" to 1,
            ),
        )
    }

    private fun otherRows(now: Long): List<String> = listOf(
        row(
            now - 30_000,
            "model" to "slow",
            "outcome" to "error:upstream-failed",
            "compact" to true,
            "total" to 500,
            "inflight" to 1,
            "async_io_drops" to 3,
        ),
        row(
            now - 20_000,
            "model" to "slow",
            "outcome" to "ok",
            "compact" to false,
            "total" to 1,
            "local_step" to 1,
            "activity_query" to 1,
            "async_io_drops" to 3,
        ),
    )

    @Test
    fun `an unknown window, a bare flag and a stray argument are refused and the default is 24h`(@TempDir tmp: Path) {
        val env = env(tmp)
        assertFalse(capture { PerfWiring.command().perf(listOf("--window", "2h"), env) }.first)
        assertFalse(capture { PerfWiring.command().perf(listOf("--window"), env) }.first, "a missing value is not 24h")
        assertFalse(capture { PerfWiring.command().perf(listOf("--window", "7d", "extra"), env) }.first)
        assertFalse(capture { PerfWiring.command().perf(listOf("7d"), env) }.first, "the label needs its flag")
        val (ok, text) = capture { PerfWiring.command().perf(emptyList(), env) }
        assertTrue(ok)
        assertTrue(text.contains("last 24h per head"), text)
        assertTrue(text.contains("no turns in this window"), text)
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
        assertFalse(capture { PerfWiring.command().perf(emptyList(), bare) }.first)
        assertFalse(Files.exists(tmp.resolve("splice.toml")), "a diagnostic materializes nothing")
    }
}
