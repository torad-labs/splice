// NEW: Oct 10, 2026 — splice ships each vendor's published list price, so a fresh install prices its turns
// (Marlin: "Every person who installs splice starts with no rates either, so they'd see 'N turns with no
// price' on every plan forever"). The prices are data in published-rates.tsv, with each vendor's source and the
// day it was read; RateTable reads them. A card in splice.toml, a head's own card, or a price the
// provider's model list publishes is always worth more than this one (ProviderConfig.catalogFor).
//
// Nov 12, 2026: the table in use is the one in the jar until a daemon takes a refreshed copy of the same file
// (PriceRefresh), so a new model is priced and a changed price is right without waiting for a release.
package splice.core.model

import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicReference

/** What [PublishedRates.take] did with a candidate reading of the file. */
public sealed class RateRefresh {
    /** [table] is in use now: the candidate, or the table already in use when the candidate says the same. */
    public data class Taken(val table: RateTable) : RateRefresh()

    /** The candidate was left aside, and [why], in words a log line carries. */
    public data class Ignored(val why: String) : RateRefresh()
}

/** The list price a vendor publishes for a model, keyed by the host its provider dials and the model's bare id,
 *  so a reseller or a local runtime that serves a model under the same name never takes the vendor's price. */
public object PublishedRates {
    private val shipped: RateTable by lazy {
        val resource = checkNotNull(javaClass.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
        RateTable(resource.bufferedReader().use { it.readText() })
    }

    private val taken = AtomicReference<RateTable?>()

    /** The table in use: the last one [take] accepted, else the one in this build. It is the same object until
     *  another is taken, so whatever was priced under it knows when the prices moved. */
    public fun table(): RateTable = taken.get() ?: shipped

    /** Whether the table in use is a refreshed one, not the one in this build. */
    public fun refreshed(): Boolean = taken.get() != null

    /** Puts [text], a reading of the same file from elsewhere, in use when it reads whole and its day is not before
     *  the day of the table in use. A text that says what the table in use says changes nothing. */
    public fun take(text: String): RateRefresh {
        val candidate = try {
            RateTable(text)
        } catch (refused: IllegalStateException) {
            return RateRefresh.Ignored("it does not read as the price table (${refused.message})")
        }
        val inUse = table()
        return when {
            candidate.updated < inUse.updated -> RateRefresh.Ignored(
                "its day, ${candidate.updated}, is before ${inUse.updated}, the day of the table in use",
            )
            candidate.sameAs(inUse) -> RateRefresh.Taken(inUse)
            else -> RateRefresh.Taken(candidate.also(taken::set))
        }
    }

    /** The card the vendor behind [baseUrl] publishes for [model], or null when it publishes none splice carries. */
    internal fun of(baseUrl: String, model: String): ModelRates? {
        val host = try {
            URI(baseUrl).host?.lowercase()
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        return table().cardOf(host, ModelTierSuffix.strip(model))
    }

    // why: the file sits at the classpath root beside compaction-reserve.tsv, the other table core ships.
    private const val RESOURCE = "/published-rates.tsv"
}
