// NEW: the plan-usage half of the status line — the segments the operator's own script drew on
// the native Claude head and every splice head now gets: effort beside the model, the session's
// spend, and the 5h / 7d windows as bars with the reset time once a bar is worth acting on.
// Sources, in order: Claude Code's own `rate_limits` (it read them off the unified headers the head
// sent, so they are already this head's windows), else the head's tracked quota straight from the
// daemon (the first tick of a session, before any response carried headers).
package splice.usage.statusline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.usage.QuotaView
import splice.usage.ApiCostText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class StatuslineBars(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val resetFormat = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ROOT)

    fun effort(root: JsonObject): String? = str((root["effort"] as? JsonObject)?.get("level"))

    /** [computed] is splice's OWN figure for this session (V4-37). It wins when present, because the
     *  client's `total_cost_usd` is priced with an ANTHROPIC card whatever head it is really talking
     *  to — the 20x error the operator reported. Except where that card IS the head's
     *  ([CostFallback.clientPriced]): there Claude Code's figure is priced at the upstream's own card,
     *  turn by turn at each turn's model, from the session's start, so it shows whenever the blob
     *  carries one and splice's shows only when it does not (V4-240 review, finding 4a).
     *
     *  V4-240: every figure carries its basis, `API est.` (ApiCostText), because none of them is a
     *  charge. And the client's own figure is shown only where [fallback] says the head's upstream IS
     *  Anthropic: on any other head it is an Anthropic price under another vendor's model (the $0.85
     *  a resumed Opus session left under GPT-6-Sol in rehearsal-resume-2), so a model with no card
     *  there says so in words. A rated model with nothing spent yet shows nothing, as before.
     *
     *  [droppedRows] is V4-45's last hop to a human. The cost reader SKIPS a perf row it cannot
     *  parse — a torn append leaves a length-extended run of NULs — so the figure above is summed
     *  from fewer turns than the session actually ran and reads LOW with nothing saying so. The
     *  count has been computed and reachable since this row's reader half landed; nothing rendered
     *  it, which is the same silence one layer up.
     *
     *  TWO THINGS THE MARKER MUST NOT DO, and both are why this is a branch rather than a suffix.
     *  It must not appear beside the CLIENT's number: dropped rows make splice's figure low and
     *  have no bearing on a figure Claude Code priced itself, so a marker there is a false alarm.
     *  And it must not make a PER-SESSION claim: the count is head-wide while the figure is one
     *  session's, so it says this figure may be low and how many rows the reader dropped — never
     *  that this session lost N turns, which would be the differently-wrong confident number V4-37
     *  exists to remove.
     *
     *  Reading, left to right: `≥` on the money is the direction (the true spend is AT LEAST this),
     *  `⚠N` is the magnitude (N rows the reader could not read). [lowerBound] draws the same `≥`
     *  with no count (V4-240 review): a turn on a model with no card, or a session older than the
     *  perf tail the reader holds. */
    fun costSegment(
        root: JsonObject,
        computed: Double? = null,
        droppedRows: Long = 0L,
        fallback: CostFallback = CostFallback(rated = false, clientPriced = true),
        lowerBound: Boolean = false,
    ): String? {
        val ours = computed?.takeIf { it > 0.0 }
        val client = num((root["cost"] as? JsonObject)?.get("total_cost_usd"))?.takeIf { it > 0.0 }
        return when {
            fallback.clientPriced && client != null -> ApiCostText.short(client, DIM, RESET)
            ours != null -> ownFigure(ours, droppedRows, lowerBound)
            fallback.rated || fallback.clientPriced -> null
            else -> DIM + ApiCostText.NO_RATE_CARD + RESET
        }
    }

    /** splice's own figure, `≥` when it may be low, with `⚠N` only when rows were dropped. */
    private fun ownFigure(usd: Double, droppedRows: Long, lowerBound: Boolean): String {
        if (!lowerBound && droppedRows <= 0L) return ApiCostText.short(usd, DIM, RESET)
        val count = if (droppedRows > 0L) " $YELLOW⚠$droppedRows$RESET" else ""
        return ApiCostText.short(usd, DIM, RESET, "$YELLOW≥$RESET") + count
    }

    /** [quotaFirst]: the line is pooled, so the tracked windows are the SELECTED account's and win;
     *  the client's rate_limits (possibly another account's) fill only a window the tracker lacks. */
    fun limitSegments(root: JsonObject, quota: QuotaView?, quotaFirst: Boolean = false): List<String> {
        val limits = root["rate_limits"] as? JsonObject
        val five = pick(window(limits, "five_hour"), quota?.fiveHour?.let { it.usedPct to it.resetsAt }, quotaFirst)
        val seven = pick(window(limits, "seven_day"), quota?.sevenDay?.let { it.usedPct to it.resetsAt }, quotaFirst)
        return listOfNotNull(segment("5h", five), segment("7d", seven))
    }

    private fun pick(client: Pair<Int, Long?>?, tracked: Pair<Int, Long?>?, quotaFirst: Boolean): Pair<Int, Long?>? =
        if (quotaFirst) tracked ?: client else client ?: tracked

    private fun window(limits: JsonObject?, key: String): Pair<Int, Long?>? {
        val w = limits?.get(key) as? JsonObject ?: return null
        val pct = num(w["used_percentage"])?.toInt() ?: return null
        return pct to (w["resets_at"] as? JsonPrimitive)?.longOrNull
    }

    private fun segment(label: String, window: Pair<Int, Long?>?): String? {
        val (pct, resetsAt) = window ?: return null
        val c = color(pct)
        val reset = resetsAt?.takeIf { pct >= WARN_PCT }?.let { at ->
            "$DIM→${resetFormat.format(Instant.ofEpochSecond(at).atZone(zone))}$RESET"
        }.orEmpty()
        return "$DIM$label$RESET $c${bar(pct)}$RESET $c$pct%$RESET$reset"
    }

    /** green under 60, yellow 60-84, bold red 85 and up — the operator's own thresholds. */
    fun color(pct: Int): String = when {
        pct >= CRITICAL_PCT -> BOLD_RED
        pct >= WARN_PCT -> YELLOW
        else -> GREEN
    }

    fun bar(pct: Int, width: Int = BAR_WIDTH): String {
        val filled = (pct.coerceIn(0, PERCENT) * width + PERCENT / 2) / PERCENT
        return "█".repeat(filled) + "░".repeat(width - filled)
    }

    private fun num(el: kotlinx.serialization.json.JsonElement?): Double? = (el as? JsonPrimitive)?.doubleOrNull

    private fun str(el: kotlinx.serialization.json.JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
}

/** V4-240: what the cost segment may show when splice has no figure of its own for the session.
 *  [rated]: this session's model has a rate card on the head, so no figure means nothing spent yet.
 *  [clientPriced]: the head's upstream is Anthropic, so Claude Code's own `total_cost_usd`, which it
 *  prices at Anthropic's API card, is priced at this upstream's card. */
internal data class CostFallback(val rated: Boolean, val clientPriced: Boolean)

private const val RESET = "[0m"
private const val DIM = "[2m"
private const val GREEN = "[32m"
private const val YELLOW = "[33m"
private const val BOLD_RED = "[1;31m"
private const val BAR_WIDTH = 8
private const val PERCENT = 100
private const val WARN_PCT = 60
private const val CRITICAL_PCT = 85
