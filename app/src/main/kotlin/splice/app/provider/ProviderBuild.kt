// PORT-OF: splice/app/Daemon.kt (Daemon.ProviderBuild) @ ed5c868 — invariants unchanged: promoted
// from a nested type to top-level so every provider builder in this package can take it without
// qualifying through Daemon; read by HeadBuildInputs, the provider arms and LaunchSpecFactory.
package splice.app.provider

import splice.core.config.SpliceConfig
import splice.core.model.HeadDiscoveredModels
import splice.core.model.ModelCatalog
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget

/** The per-head inputs every provider builder threads through — a parameter object. */
internal data class ProviderBuild(
    val key: String,
    val head: HeadConfig,
    val providerCfg: ProviderConfig,
    val catalog: ModelCatalog,
    val watchdog: WatchdogBudget,
    val cfg: SpliceConfig,
    val loginCommand: String,
    /** What the head's endpoint publishes, read when asked and never copied (V4-441): the roster is
     *  refreshed while the daemon runs, so a reader that wants the current answer holds this, not a list. */
    val discovered: HeadDiscoveredModels = HeadDiscoveredModels { emptyList() },
)
