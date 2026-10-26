// NEW: failure-handling slice 2 (2026-10-26). How a line says a time, a stretch of time, a size, a count, an amount, a
// limit window and a model (final-design.md section 10), for every sentence SpliceLines composes.
package splice.core.failure

import splice.core.util.LocalTimeText
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/** Up to a minute and a half a stretch is said in seconds, up to an hour and a half in minutes, then in hours. */
private const val MOST_SECONDS = 90L

/** An hour and a half, in seconds: the longest stretch said in minutes before it is said in hours. */
private const val MOST_MINUTES_S = 5_400L

/** why: a size is said in binary units, so a limit set as a whole number of KiB reads as that number. */
private const val BYTES_PER_KB = 1_024L

/** why: binary like the kilobyte, so the default 32 MiB body cap (Knob.MAX_REQUEST_BYTES) reads as 32 MB. */
private const val BYTES_PER_MB = 1_048_576.0

/** At ten megabytes and above a size is said whole; below, to a tenth. */
private const val WHOLE_MB = 10.0

/** The longest model name a line repeats; a longer one is called the model this request asked for. */
private const val MOST_MODEL_CHARS = 64

/** How a line says a time, a stretch of time, a size, a count, an amount, a limit window and a model. */
internal class Spoken(private val time: LocalTimeText) {
    /** A model name a person can read: no address, no key-shaped run, nothing past [MOST_MODEL_CHARS]. */
    private val shownModel = Regex("""[A-Za-z0-9][A-Za-z0-9._/:-]*""")
    private val hostShaped = Regex("""(?i)(?:[a-z0-9-]*[a-z][a-z0-9-]*\.)+[a-z]{2,}\b|://|\d{4}-\d{2}-\d{2}""")
    private val keyShaped = Regex("""[A-Za-z0-9]{24,}""")

    /** [instant] as a person reads a time: "Oct 26, 12:25 AM CDT", in the machine's zone. */
    fun at(instant: Instant): String = time.at(instant.epochSecond)

    /** [ms] as a person says a stretch of time: "31 seconds", "4 minutes", "2 hours". */
    fun span(ms: Long): String {
        val second = ChronoUnit.SECONDS.duration.toMillis()
        val seconds = (ms + second - 1) / second
        return when {
            seconds <= MOST_SECONDS -> count(seconds, "second")
            seconds < MOST_MINUTES_S -> count(nearest(seconds, ChronoUnit.MINUTES), "minute")
            else -> count(nearest(seconds, ChronoUnit.HOURS), "hour")
        }
    }

    /** [seconds] in whole [unit]s, to the nearest, a half rounding up. */
    private fun nearest(seconds: Long, unit: ChronoUnit): Long {
        val each = unit.duration.seconds
        return (seconds + each / 2) / each
    }

    fun count(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /** Names as a sentence lists them: "A", "A and B", "A, B and C". */
    fun list(names: List<String>): String =
        if (names.size < 2) names.joinToString("") else names.dropLast(1).joinToString(", ") + " and " + names.last()

    /** What a turn that waits [waitMs] from [now] says it does: a wait of an hour or more is said as a clock time. */
    fun retry(waitMs: Long, now: Instant): String = when {
        waitMs < ChronoUnit.SECONDS.duration.toMillis() -> "This turn tries again now."
        waitMs < ChronoUnit.HOURS.duration.toMillis() -> "This turn tries again in ${span(waitMs)}."
        else -> "This turn tries again at ${at(now.plusMillis(waitMs))}."
    }

    /** A token count with its thousands marked: "262,144". */
    fun tokens(n: Long): String = String.format(Locale.US, "%,d", n)

    /** A size in megabytes, in kilobytes below one, and in bytes below a kilobyte: a small limit is said as it is. */
    fun bytes(n: Long): String {
        val mb = n / BYTES_PER_MB
        return when {
            n < BYTES_PER_KB -> count(n, "byte")
            mb >= WHOLE_MB -> "${mb.roundToLong()} MB"
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb).replace(".0 MB", " MB")
            else -> "${(n + BYTES_PER_KB - 1) / BYTES_PER_KB} KB"
        }
    }

    /** A limit window as a person names it, or null where the provider named none a person knows. */
    fun window(window: LimitWindow?): String? = when (window) {
        LimitWindow.FIVE_HOUR -> "its 5-hour limit"
        LimitWindow.WEEKLY -> "its weekly limit"
        LimitWindow.MODEL_WEEKLY -> "its weekly limit for this model"
        LimitWindow.OTHER, null -> null
    }

    /** [name] as a line repeats it, or "the model this request asked for" when it is not a plain model name. */
    fun model(name: String): String {
        val plain = name.length <= MOST_MODEL_CHARS && shownModel.matches(name)
        val readable = plain && !hostShaped.containsMatchIn(name) && !keyShaped.containsMatchIn(name)
        return if (readable) name else "the model this request asked for"
    }

    /** [text] cut at a word to end, with its period, within [max] characters. */
    fun cut(text: String, max: Int): String {
        val room = text.lastIndexOf(' ', max - 1).takeIf { it > 0 } ?: max - 1
        return text.substring(0, room).trimEnd(',', ';', ':', ' ') + "."
    }
}
