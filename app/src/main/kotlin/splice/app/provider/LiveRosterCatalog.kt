// NEW: V4-440 — one immutable catalog snapshot for the latest roster and the accepted live windows.
package splice.app.provider

import splice.core.model.DiscoveredModel
import splice.core.model.LiveWindows
import splice.core.model.ModelCatalog
import splice.core.model.PublishedRates
import splice.core.model.RateTable

/** The same provider/head join as boot, cached until an input changes: the roster, the declared windows, or the
 *  price table in use, which a row with no card of its own takes its price from. No endpoint or disk I/O here. */
internal class LiveRosterCatalog(
    private val ctx: ProviderBuild,
    private val windows: LiveWindows,
) : LiveWindows {
    @Volatile private var snapshot: Snapshot? = null

    override fun current(): ModelCatalog {
        val declared = windows.current()
        val models = ctx.roster.discovered.forHead(ctx.key)
        val rates = PublishedRates.table()
        val previous = snapshot?.takeIf { it.models === models && it.windows === declared && it.rates === rates }
        if (previous != null) return previous.catalog
        val catalog = catalog(models, declared)
        snapshot = Snapshot(models, declared, rates, catalog)
        return catalog
    }

    private fun catalog(models: List<DiscoveredModel>, declared: ModelCatalog?): ModelCatalog {
        if (declared == null) return ctx.providerCfg.catalogFor(ctx.head, ctx.cfg.contextWindowOverride, models)
        // Only window declarations move with TOML. Rates, labels, auth and the declared roster remain boot decisions.
        // Rejoining discovery applies a head window to new models without raising their published ceilings.
        val exact = declared.models.associate { it.id to it.contextWindow }
        val provider = ctx.providerCfg.copy(
            models = ctx.providerCfg.models.map { row ->
                exact[row.id]?.let { row.copy(contextWindow = it) } ?: row
            },
            extraWindows = declared.windows.extraWindows,
            windowRules = declared.windows.windowRules,
            defaultContextWindow = declared.defaultContextWindow,
        )
        val head = ctx.head.copy(contextWindow = declared.windows.headWindow)
        return provider.catalogFor(head, declared.windows.headWindow ?: 0L, models)
    }

    private data class Snapshot(
        val models: List<DiscoveredModel>,
        val windows: ModelCatalog?,
        val rates: RateTable,
        val catalog: ModelCatalog,
    )
}
