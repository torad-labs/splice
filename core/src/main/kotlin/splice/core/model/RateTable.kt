// NEW: Nov 12, 2026 — one reading of published-rates.tsv: the copy in the jar, or a refreshed copy a daemon
// fetched ([PublishedRates] says which is in use). Reading it is the whole check a fetched copy gets, so every
// malformed line is refused here with an IllegalStateException and never becomes a price.
package splice.core.model

import splice.core.client.ClientVersionParser

/** The newest Claude Code a splice release was run against, as the price file names it: [claudeCode] passed on
 *  release [fromSplice], so that release and every later one may say it was tested with it. */
public data class TestedClient(val claudeCode: String, val fromSplice: String)

/** The published prices of one reading of the file, by the host a provider dials and the model's bare id. */
public class RateTable internal constructor(private val text: String) {
    private val columns = HEADER.split('\t')

    private val directives: List<List<String>> = text.lines().filter { it.startsWith(DIRECTIVE) }
        .map { line -> line.removePrefix(DIRECTIVE).split(' ').filter(String::isNotEmpty) }

    /** The day the file last changed, as the file says it: `2026-11-12`. A copy is older than another by this. */
    public val updated: String = checkNotNull(
        directives.singleOrNull { it.firstOrNull() == UPDATED }?.getOrNull(1)?.takeIf(day::matches),
    ) { "$RATES_FILE names no $UPDATED day" }

    /** The newest Claude Code a release was run against, when the file names one. */
    public val tested: TestedClient? = directives.firstOrNull { it.firstOrNull() == TESTED }?.let(::testedOf)

    private val cards: Map<Pair<String, String>, ModelRates> =
        cardsOf(text.lines().filterNot { it.startsWith(SOURCE_LINE) })

    /** How many models the table prices. */
    public val rows: Int get() = cards.size

    /** The card [host] publishes for the bare id [model], or null when the table carries none. */
    internal fun cardOf(host: String, model: String): ModelRates? = cards[host to model]

    /** Whether [other] is a reading of the same text, so that taking it would change no price. */
    internal fun sameAs(other: RateTable): Boolean = text == other.text

    private fun testedOf(words: List<String>): TestedClient {
        val versions = ClientVersionParser()
        val invalid = "invalid $RATES_FILE $TESTED line"
        check(words.size == TESTED_WORDS && words[2] == FROM_SPLICE) { invalid }
        check(versions.parse(words[1]) != null && versions.parse(words.last()) != null) { invalid }
        return TestedClient(claudeCode = words[1], fromSplice = words.last())
    }

    private fun cardsOf(lines: List<String>): Map<Pair<String, String>, ModelRates> {
        check(lines.firstOrNull() == HEADER) { "unexpected $RATES_FILE header" }
        val rows = lines.drop(1).filter(String::isNotBlank)
        check(rows.isNotEmpty()) { "$RATES_FILE has no row" }
        val parsed = rows.associate { line ->
            val values = line.split('\t')
            check(values.size == columns.size) { INVALID }
            val cells = columns.zip(values).toMap()
            (cells.getValue("host") to cells.getValue("model")) to card(RateRow(cells))
        }
        check(parsed.size == rows.size) { "duplicate $RATES_FILE row" }
        return parsed
    }

    private fun card(row: RateRow): ModelRates = ModelRates(
        input = row.price("input"),
        cacheRead = row.price("cache_read"),
        output = row.price("output"),
        cacheWrite = row.optional("cache_write"),
        cacheWriteHourly = row.optional("cache_write_1h"),
        longContext = row.optional("tier_over")?.let { over ->
            LongContextRates(
                overInputTokens = over.toLong(),
                input = row.price("tier_input"),
                cacheRead = row.price("tier_cache_read"),
                output = row.price("tier_output"),
                cacheWrite = row.optional("tier_cache_write"),
                cacheWriteHourly = row.optional("tier_cache_write_1h"),
            )
        },
        peak = row.optional("peak_factor")?.let { factor ->
            PeakHours(factor, row.hours("peak_hours_utc"), row.days("peak_days"))
        },
    )
}

/** One line of the file, its cells by column name. An empty cell is a price the vendor does not publish. */
private class RateRow(private val cells: Map<String, String>) {
    /** A number a fetched file cannot make absurd: finite, never negative, and under [MAX_NUMBER]. */
    fun optional(column: String): Double? = cells.getValue(column).takeIf(String::isNotEmpty)?.let { cell ->
        checkNotNull(cell.toDoubleOrNull()?.takeIf { it >= 0.0 && it <= MAX_NUMBER }) { INVALID }
    }

    fun price(column: String): Double = checkNotNull(optional(column)) { INVALID }

    /** `1-4,6-10`: each span's end is exclusive, so DeepSeek's 01:00 to 04:00 is `1-4`. */
    fun hours(column: String): List<IntRange> = cells.getValue(column).split(',').map { span ->
        val ends = span.split('-').map { checkNotNull(it.toIntOrNull()) { INVALID } }
        check(ends.size == 2) { INVALID }
        ends.first() until ends.last()
    }

    /** `weekdays` is Monday to Friday; an empty cell is every day. */
    fun days(column: String): Boolean = when (cells.getValue(column)) {
        WEEKDAYS -> true
        "" -> false
        else -> error(INVALID)
    }
}

// why: the name a refusal says, the file's own, whichever copy was read.
private const val RATES_FILE = "published-rates.tsv"

// why: the header names every column a row is read by, so a renamed or reordered column fails loudly. A daemon in
// the field reads the copy on main with this header, so a new column goes in a new file, never in this one.
private const val HEADER = "host\tmodel\tinput\tcache_read\toutput\tcache_write\tcache_write_1h\t" +
    "tier_over\ttier_input\ttier_cache_read\ttier_output\ttier_cache_write\ttier_cache_write_1h\t" +
    "peak_factor\tpeak_hours_utc\tpeak_days"

// why: one sentence for a row with a missing cell, a number that is no price, or a price missing where one is required.
private const val INVALID = "invalid $RATES_FILE row"

// why: lines that cite each vendor's source start with this and are not rows.
private const val SOURCE_LINE = "#"

// why: a source line that starts with this is read: `#! updated 2026-11-12`, `#! tested_claude_code 2.1.323 from_splice 0.4.0`.
private const val DIRECTIVE = "#!"
private const val UPDATED = "updated"
private const val TESTED = "tested_claude_code"
private const val FROM_SPLICE = "from_splice"

// why: the words of a tested line: its name, the Claude Code version, `from_splice`, the splice release.
private const val TESTED_WORDS = 4

// why: the one peak_days word the file uses, DeepSeek's Monday to Friday.
private const val WEEKDAYS = "weekdays"

// why: no vendor publishes a price near 100,000 USD per million tokens, and the largest number the file carries is a
// tier's token threshold in the hundreds of thousands; a cell past ten million is a damaged file, not a price.
private const val MAX_NUMBER = 10_000_000.0

private val day = Regex("""\d{4}-\d{2}-\d{2}""")
