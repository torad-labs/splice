// PORT-OF: splice/app/Daemon.kt (HeadBuildInputs.resolveHeadConfig/resolveProviderConfig/
// modelOptionsCache, Daemon.providerContext) @ ed5c868 — invariants unchanged: declared data ->
// the typed inputs a provider or launch spec needs, plus the per-head resolver that turns declared
// topology into an effective one. providerContext moved out of Daemon alongside the two resolvers
// it is the sole caller of, making this class the complete "declared data + effective per-head
// config -> typed ProviderBuild" resolver its own KDoc already claimed to be.
package splice.app.provider

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import splice.app.auth.SignInPlanner
import splice.core.config.ConfigService
import splice.core.config.SpliceConfig
import splice.core.model.DiscoveredModel
import splice.core.model.HeadDiscoveredModels
import splice.core.model.ModelCatalog
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.LiveWatchdogBudget
import splice.core.turn.WatchdogBudget
import splice.core.util.EnvReader
import splice.provider.codex.CodexLegacyKnobs
import splice.provider.grok.GrokLegacyKnobs
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Declared data -> the typed inputs a provider or launch spec needs. Every member is a pure
 * function of its arguments except [providerContext], which reads per-head config and local metadata
 * before publication. Catalog reads only join already-held facts; they never ask an endpoint.
 */
internal class HeadBuildInputs(
    private val config: ConfigService,
    private val signInPlanner: SignInPlanner,
    /** 2026-09-22: what each head's endpoint serves beyond its declared rows (ModelRosters). */
    private val discovered: HeadDiscoveredModels = HeadDiscoveredModels { emptyList() },
    private val localProbe: LocalProbeInputs = LocalProbeInputs(),
) {
    private val localModels = ConcurrentHashMap<String, List<DiscoveredModel>>()
    private val headModels = HeadDiscoveredModels { key -> localModels[key] ?: discovered.forHead(key) }

    internal fun resolveHeadConfig(
        head: HeadConfig,
        provider: ProviderConfig,
        cfg: SpliceConfig,
    ): HeadConfig = when (provider.auth.kind) {
        CHATGPT_OAUTH -> CodexLegacyKnobs().remapHead(head, cfg.port, cfg.pinnedModel)
        GROK_OAUTH -> GrokLegacyKnobs().remapHead(head, cfg.grokPort, cfg.grokModel)
        else -> head
    }

    internal fun resolveProviderConfig(provider: ProviderConfig, cfg: SpliceConfig): ProviderConfig =
        when (provider.auth.kind) {
            CHATGPT_OAUTH -> CodexLegacyKnobs().remapProvider(provider, cfg.chatgptApiBase, cfg.codexAuthPath)
            GROK_OAUTH -> GrokLegacyKnobs().remapProvider(provider, cfg.xaiApiBase)
            else -> provider
        }

    /** Pure roster -> dropdown-cache projection (the /model picker option list Claude Code caches
     *  in .claude.json — every model with its label, description, and window, so all of them appear
     *  in the picker, not just the pinned one). */
    internal fun modelOptionsCache(catalog: ModelCatalog): JsonElement = buildJsonArray {
        catalog.models.forEach { model ->
            addJsonObject {
                put("value", model.id)
                put("label", model.label.ifEmpty { model.id })
                put("description", model.description.ifEmpty { model.label.ifEmpty { model.id } })
                put("context_window", model.contextWindow)
            }
        }
    }

    /** The runtime's served rows for [key]'s head, asked the way boot asks (one sequence, [LocalProbeInputs.check]). */
    private fun askRuntime(
        key: String,
        head: HeadConfig,
        provider: ProviderConfig,
        headCfg: SpliceConfig,
    ): LocalRowsCheck {
        val base = provider.catalogFor(head, headCfg.contextWindowOverride)
        return localProbe.check(provider, localProbe.bearer(key, provider, EnvReader(System::getenv)), base)
    }

    /** A local runtime that answers again, or serves a model it did not at boot, moves the head's window with it
     *  (#399): the rows it serves now replace the ones held, only when they differ and only when it listed them.
     *  A runtime that is down or lists nothing keeps the window in force. Blocking network: never on a request
     *  thread. */
    internal fun refreshLocalModels(key: String, head: HeadConfig, provider: ProviderConfig) {
        if (!provider.isLocal) return
        val found = askRuntime(key, head, provider, config.getConfig(key)) as? LocalRowsCheck.Checked ?: return
        if (localModels[key] != found.models) localModels[key] = found.models
    }

    /** Resolve one head's build inputs against ITS OWN effective config. Heads share a single
     *  ConfigService (one JVM), so every value here must come from `getConfig(key)` — reading the
     *  global view is what made a knob tuned for one upstream govern all of them.
     *
     *  [legacyKnobsGovern] (DR-80): the legacy single-head knobs overwrite declared port/model/
     *  base/auth file ONLY for the head that is the sole one of its kind (TopologyKnobLayer.
     *  soleLegacyHeadKeys). With two-plus heads of a kind nothing was seeded, so the overwrite
     *  would hand every head the knob DEFAULTS instead of its declared TOML. */
    // `internal`, not private: DaemonPerHeadConfigTest calls this directly (via Daemon.buildInputs)
    // to pin that each head resolves against getConfig(key). No production caller outside
    // Daemon.start() (2026-07-26 review; moved out of Daemon in the 2026-08-17 decomposition).
    internal fun providerContext(
        key: String,
        head: HeadConfig,
        providerCfg: ProviderConfig,
        legacyKnobsGovern: Boolean = true,
    ): ProviderBuild {
        val headCfg = config.getConfig(key)
        val resolvedHead = if (legacyKnobsGovern) resolveHeadConfig(head, providerCfg, headCfg) else head
        val resolvedProvider = if (legacyKnobsGovern) resolveProviderConfig(providerCfg, headCfg) else providerCfg
        val localRows = if (resolvedProvider.isLocal) {
            askRuntime(key, resolvedHead, resolvedProvider, headCfg).also { found ->
                localModels[key] = (found as? LocalRowsCheck.Checked)?.models.orEmpty()
            }
        } else {
            null
        }
        // V4-116: arm the mid-output stall-re-anchor tier only where a continuation EXISTS. This is the one place
        // the fact lives — the watchdog is handed a budget, not a provider, so arming has to happen where the two
        // meet, and that is here.
        val built = WatchdogBudget(
            firstByteTimeout = headCfg.firstByteTimeoutMs.milliseconds,
            streamIdle = headCfg.streamIdleMs.milliseconds,
            totalCap = headCfg.upstreamTimeoutMs.milliseconds,
            stallReanchor = stallReanchorFor(resolvedProvider, headCfg),
        )
        return ProviderBuild(
            key = key,
            head = resolvedHead,
            providerCfg = resolvedProvider,
            catalog = catalogFor(key, head, providerCfg, legacyKnobsGovern),
            faultPlan = UpstreamFaultPlan(
                watchdog = built,
                liveWatchdog = LiveWatchdogBudget { liveBudget(key, resolvedProvider, built) },
                loginCommand = signInPlanner.signInPlan(resolvedProvider, resolvedHead, key).credentialFix,
            ),
            cfg = headCfg,
            roster = PublishedRoster(discovered = headModels, localRows = localRows),
        )
    }

    /** [built] with the two live tiers, firstByteTimeoutMs and stallReanchorMs, read from head [key]'s own config as it
     *  stands now: a turn asks this when it starts, so a PATCH governs the next turn and no restart. The other tiers
     *  (streamIdleMs, upstreamTimeoutMs) stay as built; they are restartRequired. */
    private fun liveBudget(key: String, provider: ProviderConfig, built: WatchdogBudget): WatchdogBudget {
        val now = config.getConfig(key)
        return built.copy(
            firstByteTimeout = now.firstByteTimeoutMs.milliseconds,
            stallReanchor = stallReanchorFor(provider, now),
        )
    }

    /** V4-162: the catalog head [key] boots with, as ONE derivation that [providerContext] and the live
     *  window re-read (TopologyWindows) both call. A window edited while the daemon runs therefore
     *  resolves through exactly the legacy knob remap and the per-head contextWindowOverride that boot
     *  applied, read from the same getConfig(key). */
    internal fun catalogFor(
        key: String,
        head: HeadConfig,
        providerCfg: ProviderConfig,
        legacyKnobsGovern: Boolean = true,
    ): ModelCatalog {
        val headCfg = config.getConfig(key)
        val resolvedHead = if (legacyKnobsGovern) resolveHeadConfig(head, providerCfg, headCfg) else head
        val resolvedProvider = effectiveProvider(key, providerCfg, legacyKnobsGovern)
        return resolvedProvider.catalogFor(resolvedHead, headCfg.contextWindowOverride, headModels.forHead(key))
    }

    /** The provider head [key]'s turns dial — the legacy knob remap applied exactly as
     *  [providerContext] applies it — so discovery asks the endpoint the head will actually use. */
    internal fun effectiveProvider(
        key: String,
        providerCfg: ProviderConfig,
        legacyKnobsGovern: Boolean = true,
    ): ProviderConfig =
        if (legacyKnobsGovern) resolveProviderConfig(providerCfg, config.getConfig(key)) else providerCfg

    /** V4-116: is the MID-OUTPUT STALL RE-ANCHOR tier armed for THIS head?
     *
     *  Only when the upstream has been MEASURED to continue from an assistant prefill
     *  ([QuirksConfig.reanchorPrefill] — deepseek and kimi, probed 2026-09-16; muse answers the shape
     *  with a 400). The asymmetry is the whole argument: for a prefilling head an early reap is
     *  INVISIBLE, because the round is resumed from its own salvage and the client reads one
     *  continuous message, while a head with no continuation would just end the turn ~280s sooner
     *  for the same error and could only ever cost a slow-but-alive generation. So a head that
     *  cannot be resumed keeps [WatchdogBudget.streamIdle] as its floor, exactly as before this tier
     *  existed — the "hard floor for providers with prefill off" half of the row.
     *
     *  The value is read from `getConfig(key)`, never the global view: heads share one ConfigService,
     *  so a tier tuned for one upstream must not govern all of them. `0` is the documented "off"
     *  spelling and is coerced to INFINITE rather than to a zero-length tier, which would reap every
     *  mid-output round on its very first poll. */
    private fun stallReanchorFor(provider: ProviderConfig, headCfg: SpliceConfig): Duration {
        if (provider.quirks.reanchorPrefill != true) return Duration.INFINITE
        val ms = headCfg.stallReanchorMs
        return if (ms <= 0) Duration.INFINITE else ms.milliseconds
    }
}
