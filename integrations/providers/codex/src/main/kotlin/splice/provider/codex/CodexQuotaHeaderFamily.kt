// NEW: Codex x-codex-* quota headers, sorted into the two client slots by window length.
package splice.provider.codex

import splice.core.usage.EPOCH_MILLIS_FLOOR
import splice.core.usage.FIVE_HOURS_SECONDS
import splice.core.usage.QuotaHeaderRead
import splice.core.usage.QuotaSlots
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.usage.SEVEN_DAYS_SECONDS
import splice.core.util.WallClock
import splice.upstream.retry.QuotaHeaderFamily

public class CodexQuotaHeaderFamily : QuotaHeaderFamily {
    private val slots = QuotaSlots()

    override fun snapshot(header: QuotaHeaderRead, clock: WallClock): QuotaSnapshot? {
        val windows = listOfNotNull(
            window(header, "primary", FIVE_HOURS_SECONDS, clock),
            window(header, "secondary", SEVEN_DAYS_SECONDS, clock),
        )
        return if (windows.isEmpty()) {
            null
        } else {
            slots.snapshot(windows, header("x-codex-plan-type"), clock())
        }
    }

    private fun window(
        h: QuotaHeaderRead,
        which: String,
        defaultSeconds: Long,
        clock: WallClock,
    ): QuotaWindow? {
        val used = h("x-codex-$which-used-percent")?.toDoubleOrNull() ?: return null
        val minutes = h("x-codex-$which-window-minutes")?.toLongOrNull()
        val resetAt = h("x-codex-$which-reset-at")?.toDoubleOrNull()?.let(::epochSeconds)
            ?: h("x-codex-$which-reset-after-seconds")?.toLongOrNull()?.let { clock() / MILLIS + it }
        return QuotaWindow(used, resetAt, minutes?.let { it * SECONDS_PER_MINUTE } ?: defaultSeconds)
    }

    /** Seconds when below 1e11; otherwise milliseconds. */
    private fun epochSeconds(value: Double): Long =
        if (value < EPOCH_MILLIS_FLOOR) value.toLong() else (value / MILLIS).toLong()
}

private const val MILLIS = 1000L
private const val SECONDS_PER_MINUTE = 60L
