// PORT-OF: splice/app/Daemon.kt (Daemon.ProviderBuild) @ ed5c868 — invariants unchanged: promoted
// from a nested type to top-level so every provider builder in this package can take it without
// qualifying through Daemon; read by HeadBuildInputs, the provider arms and LaunchSpecFactory.
package splice.app.provider

import splice.app.provider.local.LocalRowsCheck
import splice.core.config.SpliceConfig
import splice.core.model.HeadDiscoveredModels
import splice.core.model.ModelCatalog
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.LiveWatchdogBudget
import splice.core.turn.WatchdogBudget
import splice.upstream.retry.CLIENT_WALL_GRACE_MS
import splice.upstream.retry.MAX_HOLD_MS

/** The per-head inputs every provider builder threads through — a parameter object. */
internal data class ProviderBuild(
    val key: String,
    val head: HeadConfig,
    val providerCfg: ProviderConfig,
    val catalog: ModelCatalog,
    val cfg: SpliceConfig,
    val faultPlan: UpstreamFaultPlan,
    val roster: PublishedRoster = PublishedRoster(),
)

/** What a head does when its upstream goes wrong: how long it waits, and the command that fixes the credential. */
internal data class UpstreamFaultPlan(
    val watchdog: WatchdogBudget,
    val loginCommand: String,
    /** [watchdog] with the live knobs (firstByteTimeoutMs, stallReanchorMs) read as they stand when a turn asks;
     *  the budget as built where nothing is live. */
    val liveWatchdog: LiveWatchdogBudget = LiveWatchdogBudget { watchdog },
) {
    /** The client timeout a session must outlive to see a turn here end, in milliseconds: DERIVED from the whole-turn
     *  cap (never a second hand-maintained number), since the proxy's wall is the one that names the verdict, and from
     *  the longest hold (failure-handling slice 5), so a turn held until a reset up to seven days away is still the
     *  client's request when the reset comes. A head's launch and a launch on the door both plant it (#791). */
    val clientWallMs: Long get() = watchdog.totalCap.inWholeMilliseconds + MAX_HOLD_MS + CLIENT_WALL_GRACE_MS
}

/** What the head's endpoint published, as the provider builders read it. */
internal data class PublishedRoster(
    /** What the head's endpoint publishes, read when asked and never copied (V4-441): the roster is
     *  refreshed while the daemon runs, so a reader that wants the current answer holds this, not a list. */
    val discovered: HeadDiscoveredModels = HeadDiscoveredModels { emptyList() },
    /** Metadata already read before publishing the catalog; provider construction never repeats it. */
    val localRows: LocalRowsCheck? = null,
)
