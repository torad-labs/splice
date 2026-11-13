// NEW: Nov 12, 2026 — `splice status` says which price table a daemon on this machine prices with: the one in the
// build, or one the daemon fetched from the project's main branch, with when and from where (PriceRefresh).
package splice.app.cli.status

import splice.core.model.PublishedRates
import splice.core.model.RateRefresh
import splice.lifecycle.prices.PRICE_REFRESH_HOST
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The line, read from the copy the daemon keeps at [copy]: this process takes it as a daemon's start does, so the
 *  day it names is the day of the table in use. [on] is splice.toml's `refresh_prices`, absent reading as on. */
internal class PriceTableLine(
    private val copy: Path,
    private val on: Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val moment = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    fun line(): String {
        val keptAt = keptAt()
        val table = "prices   the table of ${PublishedRates.table().updated}"
        val off = if (on) "" else "; refresh is off (refresh_prices = false)"
        return when {
            keptAt != null -> "$table, refreshed ${moment.format(keptAt.atZone(zone))} from $PRICE_REFRESH_HOST$off"
            on -> "$table, the copy in this build; not refreshed from $PRICE_REFRESH_HOST yet"
            else -> "$table, the copy in this build$off"
        }
    }

    /** When the kept copy was fetched, when it is one a daemon's start takes. Null with no copy, with one a start
     *  leaves aside, and with one that cannot be read, which a start leaves aside too and says so in the daemon log. */
    private fun keptAt(): Instant? {
        if (!Files.isRegularFile(copy)) return null
        return try {
            Files.readString(copy).takeIf { PublishedRates.take(it) is RateRefresh.Taken }
                ?.let { Files.getLastModifiedTime(copy).toInstant() }
        } catch (_: IOException) {
            null
        }
    }
}
