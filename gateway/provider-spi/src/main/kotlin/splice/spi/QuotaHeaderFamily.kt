// NEW: per-vendor upstream quota-header family. Core owns the unified Anthropic family;
// each provider module owns the headers only it sends.
package splice.spi

import splice.core.usage.QuotaHeaderRead
import splice.core.usage.QuotaSnapshot
import splice.core.util.WallClock

/** Decode one vendor header family into the two client quota slots, or null when absent. */
public fun interface QuotaHeaderFamily {
    public fun snapshot(header: QuotaHeaderRead, clock: WallClock): QuotaSnapshot?
}
