// Oct 11, 2026 (fin): an hour recorded before the plan split is split from the request log wherever the log holds
// the hour's own requests, account by account; an hour the log does not hold keeps its mixed figures, unsplit.
package splice.app.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.WallClock
import splice.head.usage.AccountOnPlan
import splice.usage.economics.EconomicsRead
import java.nio.file.Files
import java.nio.file.Path

class EconomicsPlanSplitFromLogTest {
    private val onPlan = AccountOnPlan { it == "plan" }
    private val planRow = withAccount("plan")
    private val keyRow = withAccount("key")

    private fun withAccount(account: String) =
        MODELED_WORK_ROW.replace("\"model\":\"synthetic\"", "\"model\":\"synthetic\",\"account\":\"$account\"")

    @Test
    fun `hours the log holds are split by account and an hour it does not hold stays unsplit`(@TempDir dir: Path) {
        val file = dir.resolve("head-perf.jsonl")
        Files.writeString(
            file,
            atHour(19 * PROBE_HOUR + 123, planRow) +
                atHour(20 * PROBE_HOUR + 123, planRow, keyRow) +
                atHour(21 * PROBE_HOUR + 123, planRow),
        )
        // The rollup as an install before the split wrote it: the plan request's cost was folded into the hour's.
        var now = 19 * PROBE_HOUR + 123
        val before = store(dir, WallClock { now })
        before.record(turn("synthetic", 5, 100, 50, 3L to 2L))
        now = 20 * PROBE_HOUR + 123
        before.record(turn("synthetic", 5, 100, 50, 3L to 2L))
        before.record(turn("synthetic", 5, 100, 50, 3L to 2L))
        now = 21 * PROBE_HOUR + 123
        before.record(turn("synthetic", 5, 100, 50, 3L to 2L))
        before.record(turn("synthetic", 5, 100, 50, 3L to 2L))
        before.flushNow()
        val path = dir.resolve("economics.json")
        Files.writeString(path, Files.readString(path).replace(Regex(""","plan_[a-z_]+":[0-9.]+"""), ""))

        val after = store(dir, WallClock { now }, onPlan)
        val read = assertInstanceOf(
            EconomicsRead.Rows::class.java,
            EconomicsStoreSource(after, PerfRowsFileSource(file), probePrice).read(),
        )

        val twenty = read.rows.single { it.hour == 20 * PROBE_HOUR }.cost
        val one = twenty.costUsd!!
        assertEquals(1L, twenty.planTurns, "the plan account's request, from the log")
        assertEquals(one, twenty.planValueUsd!!, 1e-12, "its API-rate value equals the key request's cost")
        assertEquals(1.626e-4, one, 1e-9, "the key request alone, not both")
        assertEquals(0L, twenty.unpricedTurns)
        val twentyOne = read.rows.single { it.hour == 21 * PROBE_HOUR }.cost
        assertNull(twentyOne.planTurns, "the log holds one request of the two the rollup counted, so it cannot say")
    }
}
