// NEW: Oct 10, 2026 — splice ships each vendor's published list price, so a fresh install prices its turns
// (Marlin: "Every person who installs splice starts with no rates either, so they'd see 'N turns with no
// price' on every plan forever"). The prices are data in published-rates.tsv, with each vendor's source and the
// day it was read; this file only reads them. A card in splice.toml, a head's own card, or a price the
// provider's model list publishes is always worth more than this one (ProviderConfig.catalogFor).
package splice.core.model

import java.net.URI
import java.net.URISyntaxException

/** The list price a vendor publishes for a model, keyed by the host its provider dials and the model's bare id,
 *  so a reseller or a local runtime that serves a model under the same name never takes the vendor's price. */
internal object PublishedRates {
    private val cards: Map<Pair<String, String>, ModelRates> by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
        val lines = resource.bufferedReader().use { it.readLines() }.filterNot { it.startsWith(COMMENT) }
        check(lines.firstOrNull() == HEADER) { "unexpected $RESOURCE header" }
        val rows = lines.drop(1).filter(String::isNotBlank)
        val parsed = rows.associate { line ->
            val values = line.split('\t')
            check(values.size == columns.size) { INVALID }
            val cells = columns.zip(values).toMap()
            (cells.getValue("host") to cells.getValue("model")) to card(Row(cells))
        }
        check(parsed.size == rows.size) { "duplicate $RESOURCE row" }
        parsed
    }

    private val columns = HEADER.split('\t')

    /** The card the vendor behind [baseUrl] publishes for [model], or null when it publishes none splice carries. */
    fun of(baseUrl: String, model: String): ModelRates? {
        val host = try {
            URI(baseUrl).host?.lowercase()
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        return cards[host to ModelTierSuffix.strip(model)]
    }

    private fun card(row: Row): ModelRates = ModelRates(
        input = row.price("input"),
        cacheRead = row.price("cache_read"),
        output = row.price("output"),
        cacheWrite = row.optional("cache_write"),
        longContext = row.optional("tier_over")?.let { over ->
            LongContextRates(
                overInputTokens = over.toLong(),
                input = row.price("tier_input"),
                cacheRead = row.price("tier_cache_read"),
                output = row.price("tier_output"),
                cacheWrite = row.optional("tier_cache_write"),
            )
        },
    )

    /** One line of the file, its cells by column name. An empty cell is a price the vendor does not publish. */
    private class Row(private val cells: Map<String, String>) {
        fun optional(column: String): Double? = cells.getValue(column).takeIf(String::isNotEmpty)?.toDouble()

        fun price(column: String): Double = checkNotNull(optional(column)) { INVALID }
    }

    // why: the file sits at the classpath root beside compaction-reserve.tsv, the other table core ships.
    private const val RESOURCE = "/published-rates.tsv"

    // why: the header names every column a row is read by, so a renamed or reordered column fails loudly.
    private const val HEADER = "host\tmodel\tinput\tcache_read\toutput\tcache_write\t" +
        "tier_over\ttier_input\ttier_cache_read\ttier_output\ttier_cache_write"

    // why: one sentence for a row with a missing cell or a price that is missing where one is required.
    private const val INVALID = "invalid $RESOURCE row"

    // why: lines that cite each vendor's source start with this and are not rows.
    private const val COMMENT = "#"
}
