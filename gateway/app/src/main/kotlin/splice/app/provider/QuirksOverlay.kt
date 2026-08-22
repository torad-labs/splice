// PORT-OF: splice/app/Daemon.kt (HeadBuildInputs.chatQuirks/toolDeferralPolicy/foldConfigFrom,
// ProviderAssembly.responsesQuirks, PassthroughAssembly.passthroughQuirks) @ ed5c868 — invariants
// unchanged: these five are one job under three spellings — each maps a head's TOML
// [providers.*.quirks] onto its dialect's base quirk profile — and collecting them here is what
// lets every provider arm stop importing a dialect package just to build its quirks.
package splice.app.provider

import splice.core.config.SpliceConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.ToolSurfaceConfig
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.responses.DEFAULT_MARKER_TEXT
import splice.dialect.responses.FoldConfig
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ToolDeferralPolicy

private const val MIN_TOOL_SURFACE_FLOOR = 1
private const val MAX_TOOL_SEARCH_LIMIT = 50
private const val MAX_TOOL_SEARCH_ROUNDS = 5

/**
 * Declared TOML -> effective dialect quirk profile, for all three dialects. Every member is a pure
 * function of its arguments, which is why they lived scattered across HeadBuildInputs,
 * ProviderAssembly and PassthroughAssembly before the decomposition gave them one owner.
 */
internal class QuirksOverlay {

    /** Overlay the head's TOML [providers.*.quirks] onto a provider's base quirk profile. */
    // quirks.effortCeiling is intentionally not passed: the effort ladder clamps per provider.
    internal fun responsesQuirks(
        providerCfg: ProviderConfig,
        base: ResponsesQuirks,
        cfg: SpliceConfig,
    ): ResponsesQuirks = base.withToml(
        store = providerCfg.quirks.store,
        cacheKey = providerCfg.quirks.cacheKey,
        summaryField = providerCfg.quirks.summaryField,
        compactEffort = providerCfg.quirks.compactEffort,
        toolChoice = providerCfg.quirks.toolChoice,
    ).withReasoningCacheToml(providerCfg.quirks.reasoningCache)
        .withParallelToolCallsToml(providerCfg.quirks.parallelToolCalls)
        .withWebSocketToml(providerCfg.quirks.webSocket)
        .withToolSurfaceToml(toolDeferralPolicy(providerCfg.quirks.toolSurface, cfg.toolSurfaceOff))

    /** Overlay the head's TOML [providers.*.quirks] onto a passthrough head's BASE quirk profile.
     *  Absent (null) keeps the base, which is what makes a splice.toml written before these knobs
     *  existed keep serving a kimi head unchanged; an explicitly-set knob wins. Same shape as
     *  [responsesQuirks], and the mapping lives HERE, at the assembly point, so the
     *  dialect never imports a topology config type. */
    internal fun passthroughQuirks(providerCfg: ProviderConfig, base: PassthroughQuirks): PassthroughQuirks =
        base.copy(
            mapThinkingToAdaptive = providerCfg.quirks.mapThinkingAdaptive ?: base.mapThinkingToAdaptive,
            compactEffort = providerCfg.quirks.compactEffort ?: base.compactEffort,
            stripSamplingParams = providerCfg.quirks.stripSamplingParams ?: base.stripSamplingParams,
            mfjsSanitize = providerCfg.quirks.mfjs ?: base.mfjsSanitize,
            // Absent (null) keeps the base. Empty (`block_allowlist = []`) is the ONLY spelling that
            // turns a base allowlist OFF. takeIf-then-?: collapsed those two, so empty restored the
            // base and there was no operator spelling that cleared it. Never materialize an empty
            // set: that would drop every content block of every message, silently.
            blockAllowlist = when (val list = providerCfg.quirks.blockAllowlist) {
                null -> base.blockAllowlist
                else -> list.takeIf { it.isNotEmpty() }?.toSet()
            },
            stripCacheControl = providerCfg.quirks.stripCacheControl ?: base.stripCacheControl,
            synthesizeSignatures = providerCfg.quirks.synthesizeSignatures ?: base.synthesizeSignatures,
        )

    /** TOML table -> dialect policy. Null (absent table, enabled=false, or the daemon-wide kill
     *  switch) = feature off. The mapping lives HERE, at the assembly point, so the dialect never
     *  imports a topology config type — the same reason withToml takes primitives.
     *
     *  Clamped (review 2026-07-24): ToolSurfaceConfig does no validation of its own, so an operator
     *  TOML typo (e.g. `search_limit = 0`) reached `coerceIn(1, policy.searchLimit)` in
     *  ResponsesToolSearchController unclamped and THREW — a client-visible failed turn on every
     *  round that searched, the one place this feature's own NEVER-BELOW-STATUS-QUO law broke.
     *  Clamping here (like every other numeric knob — ConfigService.normalize) makes a bad value
     *  un-armable instead of a live crash. */
    internal fun toolDeferralPolicy(t: ToolSurfaceConfig?, globalOff: Boolean): ToolDeferralPolicy? = when {
        t == null -> null
        !t.enabled -> null
        globalOff -> null
        else -> ToolDeferralPolicy(
            deferPrefixes = t.deferPrefixes,
            defer = t.defer.toSet(),
            eager = t.eager.toSet(),
            minDeferred = t.minDeferred.coerceAtLeast(MIN_TOOL_SURFACE_FLOOR),
            searchLimit = t.searchLimit.coerceIn(MIN_TOOL_SURFACE_FLOOR, MAX_TOOL_SEARCH_LIMIT),
            searchRounds = t.searchRounds.coerceIn(MIN_TOOL_SURFACE_FLOOR, MAX_TOOL_SEARCH_ROUNDS),
        )
    }

    // Reasoning-continuation folding config (codex 518n-2), threaded from ConfigService like the other
    // reasoning knobs. An empty model set = feature off.
    internal fun foldConfigFrom(cfg: SpliceConfig): FoldConfig = FoldConfig(
        models = cfg.foldReasoningModels,
        maxContinue = cfg.foldMaxContinue,
        markerText = cfg.foldMarkerText.ifEmpty { DEFAULT_MARKER_TEXT },
        maxTierN = cfg.foldMaxTier,
    )
}
