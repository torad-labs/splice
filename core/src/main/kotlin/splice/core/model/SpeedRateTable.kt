// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 5) — the Fast and Ultrafast cards splice ships, read from
// published-speed-rates.tsv beside published-rates.tsv. A file of their own because installed daemons fetch
// published-rates.tsv from main every hour and refuse a header they do not know (RateTable), so a tier column there
// would leave every installed daemon on its build's prices. Read like that file, each malformed line refused.
package splice.core.model

import splice.core.speed.Speed
import splice.core.speed.SpeedWords

/** The published speed cards, by the host a provider dials, the model's bare id and the speed. */
internal class SpeedRateTable(text: String) {
    private val cards: Map<Triple<String, String, Speed>, SpeedRates> = cardsOf(
        text.lines().filterNot { it.startsWith(SOURCE_LINE) },
    )

    /** [card], which [host] publishes for the bare id [model], with the speed cards the table carries for it. A card
     *  that names its own speed card keeps it: a price splice.toml declares always wins over a shipped one. */
    fun withSpeeds(host: String, model: String, card: ModelRates): ModelRates = card.copy(
        fast = card.fast ?: cards[Triple(host, model, Speed.FAST)],
        ultrafast = card.ultrafast ?: cards[Triple(host, model, Speed.ULTRAFAST)],
    )

    private fun cardsOf(lines: List<String>): Map<Triple<String, String, Speed>, SpeedRates> {
        check(lines.firstOrNull() == SPEED_HEADER) { "unexpected $SPEED_RATES_FILE header" }
        val columns = SPEED_HEADER.split('\t')
        val rows = lines.drop(1).filter(String::isNotBlank)
        val parsed = rows.associate { line ->
            val values = line.split('\t')
            check(values.size == columns.size) { SPEED_INVALID }
            val cells = columns.zip(values).toMap()
            val speed = SpeedWords.named(cells.getValue("speed"))?.takeUnless { it == Speed.STANDARD }
            val row = RateRow(cells, SPEED_INVALID)
            val key = Triple(cells.getValue("host"), cells.getValue("model"), checkNotNull(speed) { SPEED_INVALID })
            key to SpeedRates(
                input = row.price("input"),
                cacheRead = row.price("cache_read"),
                output = row.price("output"),
                cacheWrite = row.optional("cache_write"),
            )
        }
        check(parsed.size == rows.size) { "duplicate $SPEED_RATES_FILE row" }
        return parsed
    }
}

// why: the name a refusal says.
private const val SPEED_RATES_FILE = "published-speed-rates.tsv"

// why: the header names every column a row is read by, so a renamed or reordered column fails loudly.
private const val SPEED_HEADER = "host\tmodel\tspeed\tinput\tcache_read\toutput\tcache_write"

// why: one sentence for a row with a missing cell, a speed that is not fast or ultrafast, or a number that is no price.
private const val SPEED_INVALID = "invalid $SPEED_RATES_FILE row"
