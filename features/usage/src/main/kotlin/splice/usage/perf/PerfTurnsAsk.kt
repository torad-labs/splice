// NEW: Oct 12, 2026 — what one /api/perf/turns request asks for, after validation, moved out of PerfRoutes so the
// route holds the reading and these hold the shapes (the push of 8ab63f4c1: PerfRoutes.kt in the concentration band HIGH).
package splice.usage.perf

import kotlinx.serialization.json.JsonObject
import java.time.ZoneId

/** Which whole-window figures the answer carries beside its rows. [peak] is the most requests held at once over
 *  EVERY matching row, not the clamped newest. */
internal data class AskedFigures(val usage: Boolean = true, val peak: Boolean = false, val names: Boolean = false) {
    /** The usage block this read asked for: `usage=names` is the menus' read, the names alone without the priced fold
     *  (TurnNames); `usage=0` leaves the block out. */
    fun usageJson(matching: List<PerfRow>, gaps: TurnPriceGap, zone: ZoneId, reading: AccountReading): JsonObject? =
        when {
            names -> TurnNames(matching, reading).json()
            usage -> TurnUsage(matching, gaps, zone, reading).json()
            else -> null
        }
}

/** `?order=`: the n rows a clamp keeps. `price` ranks the WHOLE matching window by what each request costs at the
 *  head's published API rates (a key's cost_usd, a plan's plan_value_usd), unpriced requests last, ties newest first. */
internal enum class TurnsOrder(val wire: String) {
    NEWEST("newest"),
    PRICE("price"),
}

/** The rows the clamp keeps: [n] of them, and which [n] of the matching window, the newest or the highest priced. */
internal data class AskedRows(val n: Int, val order: TurnsOrder = TurnsOrder.NEWEST)

/** The window one request asks for, after validation. A named pair and not a `Pair<Long, Int>`: the
 *  two are read straight into a cutoff and a clamp, and a positional swap of same-shaped values is the
 *  defect the row's own named-argument rule exists for. */
internal data class AskedWindow(
    val since: Long,
    val rows: AskedRows,
    val filter: TurnsFilter,
    val zone: ZoneId,
    val figures: AskedFigures = AskedFigures(),
    /** More questions of the SAME window, each its own filter and clamp, answered from the one read of it. */
    val asks: List<AskedWindow> = emptyList(),
    /** The project snapshot this request's rows are tagged from. */
    val tags: ProjectTags = NoProjectTags,
)
