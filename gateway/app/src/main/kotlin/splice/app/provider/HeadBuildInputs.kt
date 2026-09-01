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
import splice.app.SignInPlanner
import splice.core.config.ConfigService
import splice.core.config.SpliceConfig
import splice.core.model.ModelCatalog
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import kotlin.time.Duration.Companion.milliseconds

/**
 * Declared data -> the typed inputs a provider or launch spec needs. Every member is a pure
 * function of its arguments except [providerContext], which reads [config] against a head KEY —
 * heads share one ConfigService (one JVM), so every value here must come from `getConfig(key)`.
 */
internal class HeadBuildInputs(
    private val config: ConfigService,
    private val signInPlanner: SignInPlanner,
) {

    internal fun resolveHeadConfig(
        head: HeadConfig,
        provider: ProviderConfig,
        cfg: SpliceConfig,
    ): HeadConfig = when (provider.auth.kind) {
        CHATGPT_OAUTH -> head.copy(port = cfg.port, pinnedModel = cfg.pinnedModel)
        GROK_OAUTH -> head.copy(port = cfg.grokPort, pinnedModel = cfg.grokModel)
        else -> head
    }

    internal fun resolveProviderConfig(provider: ProviderConfig, cfg: SpliceConfig): ProviderConfig =
        when (provider.auth.kind) {
            CHATGPT_OAUTH -> provider.copy(baseUrl = cfg.chatgptApiBase)
            GROK_OAUTH -> provider.copy(baseUrl = cfg.xaiApiBase)
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

    /** Resolve one head's build inputs against ITS OWN effective config. Heads share a single
     *  ConfigService (one JVM), so every value here must come from `getConfig(key)` — reading the
     *  global view is what made a knob tuned for one upstream govern all of them.
     *
     *  [legacyKnobsGovern] (DR-80): the legacy single-head knobs overwrite declared port/model/
     *  base ONLY for the head that is the sole one of its kind (TopologyKnobLayer.
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
        return ProviderBuild(
            key = key,
            head = resolvedHead,
            providerCfg = resolvedProvider,
            catalog = resolvedProvider.catalogFor(resolvedHead, headCfg.contextWindowOverride),
            watchdog = WatchdogBudget(
                firstByteTimeout = headCfg.firstByteTimeoutMs.milliseconds,
                streamIdle = headCfg.streamIdleMs.milliseconds,
                totalCap = headCfg.upstreamTimeoutMs.milliseconds,
            ),
            cfg = headCfg,
            loginCommand = signInPlanner.signInPlan(resolvedProvider, resolvedHead, key).command,
        )
    }
}
