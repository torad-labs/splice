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
import splice.core.model.ModelCatalog
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import splice.provider.codex.CodexLegacyKnobs
import splice.provider.grok.GrokLegacyKnobs
import kotlin.time.Duration
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
        return ProviderBuild(
            key = key,
            head = resolvedHead,
            providerCfg = resolvedProvider,
            catalog = catalogFor(key, head, providerCfg, legacyKnobsGovern),
            watchdog = WatchdogBudget(
                firstByteTimeout = headCfg.firstByteTimeoutMs.milliseconds,
                streamIdle = headCfg.streamIdleMs.milliseconds,
                totalCap = headCfg.upstreamTimeoutMs.milliseconds,
                // V4-116: arm the mid-output stall-re-anchor tier only where a continuation EXISTS.
                // This is the one place the fact lives — the watchdog is handed a budget, not a
                // provider, so arming has to happen where the two meet, and that is here.
                stallReanchor = stallReanchorFor(resolvedProvider, headCfg),
            ),
            cfg = headCfg,
            loginCommand = signInPlanner.signInPlan(resolvedProvider, resolvedHead, key).command,
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
        val resolvedProvider = if (legacyKnobsGovern) resolveProviderConfig(providerCfg, headCfg) else providerCfg
        return resolvedProvider.catalogFor(resolvedHead, headCfg.contextWindowOverride)
    }

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
