// Oct 11, 2026 — the turns route filters and counts over compact rows and reads whole rows back for the ones it shows.
// A window larger than the whole-row tier holds must answer a repeat read from memory, and every answer must equal the
// one read from whole rows.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.failure.PersistedCause
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.turn.ErrorType
import splice.usage.UsageHead
import splice.usage.UsageHeadSinks
import splice.usage.UsageHeadWarn
import splice.usage.perf.PerfRowsSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.nio.file.Files
import java.nio.file.Path

private const val ROWS = 6_000
private const val START = 1_800_000_000_000L
private const val SPAN_MS = 7L * 24 * 60 * 60 * 1_000

class PerfCompactTierTest {
    private fun outcome(i: Int): String = when (i % 11) {
        0 -> OutcomeTags.failure(ErrorType.OVERLOADED)
        1 -> OutcomeTag.TURN_CAP.wire
        2 -> OutcomeTags.failure(ErrorType.INVALID_REQUEST)
        else -> "ok"
    }

    private fun cause(i: Int): String? = when {
        i % 11 != 0 -> null
        i % 22 == 0 -> PersistedCause.UPSTREAM_STALLED.name
        else -> PersistedCause.UPSTREAM_TRUNCATED.name
    }

    /** One request: header facts and ids that are unique per row, the waits the tests read, and numeric noise. */
    private fun line(i: Int): String {
        val ts = START + i * (SPAN_MS / ROWS)
        val total = 4_000 + i % 90_000
        val firstByte = 300 + i % 700
        val began = if (i % 7 == 0) ts - total + firstByte + 100 else ts - total + 10
        val noise = (0 until 36).joinToString(",") { "\"noise_$it\":${i + it}" }
        val header = "\"ts\":$ts,\"outcome\":\"${outcome(i)}\"" + (cause(i)?.let { ",\"cause\":\"$it\"" } ?: "")
        val facts = "\"model\":\"m${i % 3}\",\"session\":\"s${i % 17}\",\"session_id\":\"sid-${i % 17}-full\"," +
            "\"account\":\"a${i % 2}\",\"compact\":${i % 13 == 0}"
        val ids = "\"turn\":\"t-$i\",\"turn_id\":\"r-$i\",\"response_message_id\":\"resp-$i\"," +
            "\"provider_message\":\"upstream words $i\""
        val waits = "\"total\":$total,\"first_byte\":$firstByte," +
            "\"up_gap_max_ms\":${if (i % 5 == 0) 30_000L + i % 70_000 else 0L},\"up_gap_max_start_epoch_ms\":$began," +
            "\"stall_ms\":${if (i % 9 == 0) 40_000 else 0},\"admit_wait_ms\":${if (i % 4 == 0) i % 5_000 else 0}," +
            "\"reanchors\":${i % 3},\"reanchors_from_scratch\":${if (i % 6 == 0) 1 else 0}," +
            "\"local_step\":${if (i % 29 == 0) 1 else 0}"
        val tokens = "\"in_tokens\":${1_000 + i},\"out_tokens\":${20 + i % 50},\"cached_tokens\":${i % 400}"
        return "{$header,$facts,$ids,$waits,$tokens,$noise}"
    }

    private fun history(file: Path) {
        Files.write(file, (0 until ROWS).map(::line))
    }

    private fun head(source: PerfRowsSource) = UsageHead(
        "synthetic",
        "claude-synthetic",
        HeadUsageSource { UsageView(0, 0, null) },
        warn = UsageHeadWarn(warnPct = 80, warnTokens5h = 0),
        sinks = UsageHeadSinks(perfRows = source),
    )

    @Test
    fun `a window larger than the whole-row tier answers a repeat from memory and equals the whole-row answer`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("synthetic-perf.jsonl")
        history(file)
        // A whole-row tier far below the window: every read of it must start from the disk again.
        val tiny = PerfRowsCache(limitBytes = 1_024L * 1_024)
        val source = PerfRowsFileSource(file, cache = tiny)
        val compact = PerfTurnsRoute(head(source))
        val whole = PerfTurnsRoute(head(PerfRowsSource { PerfRowsFileSource(file).window(it) }))
        val since = START

        val first = compact.turns(since, n = 3).toString()
        val parsed = source.parsedLines
        val second = compact.turns(since, n = 3).toString()
        assertEquals(first, second)
        assertTrue(source.parsedLines - parsed <= 3, "a repeat read decodes only the rows it shows")

        val asks = "&ask=test%3Dwaited%26silence_ms%3D60000%26n%3D1%26usage%3D0"
        val queries = listOf(
            "",
            "&outcome=failed",
            "&test=gaveup",
            "&test=overloaded",
            "&test=cutoff",
            "&test=silent",
            "&test=waited&silence_ms=60000",
            "&silence_ms=40000&queued_ms=1",
            "&resumed=1&started_over=1",
            "&model=m1&account=a0",
            "&session=s3&local=0&compact=1",
            "&peak=1&n=40",
            "&unattributed=model",
            "&from=${START + SPAN_MS / 2}&until=${START + SPAN_MS / 2 + SPAN_MS / 10}&n=2000",
            asks,
        )
        for (query in queries) {
            val filters = query.split("&").filter { it.isNotEmpty() }.associate {
                it.substringBefore("=") to it.substringAfter("=")
            }
            // the same request twice: asks repeat the key, so a request with asks goes through the raw query.
            val n = filters["n"]?.toInt() ?: 5
            val expected = whole.turns(since, n, filters)
            val actual = compact.turns(since, n, filters)
            assertEquals(expected.toString(), actual.toString(), "the answer for '$query' reads the same")
        }
    }
}
