// PORT-OF: splice/app/Daemon.kt (PassthroughAssembly.passthroughProviderFor) @ ed5c868 —
// the anthropic-passthrough construction site — the dialect's ONE provider fed assembly-selected
// base data plus the TOML quirk/header overlays (now QuirksOverlay.passthroughQuirks).
package splice.app.provider

import splice.core.auth.RefreshableAuthProvider
import splice.core.topology.ProviderConfig
import splice.dialect.anthropic.IdentityHeaders
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning

/** The headers a passthrough head presents: the provider's defaults [base] (overridden by an operator's TOML) and,
 *  for Kimi only, the runtime device [identity]. */
internal class PassthroughHeaders(
    private val base: Map<String, String> = emptyMap(),
    val identity: IdentityHeaders = IdentityHeaders { emptyMap() },
) {
    /** Base FIRST so an operator's TOML overrides it, and absent TOML keeps the head serving: these headers used to
     *  be hardcoded in the provider, so a splice.toml written before extra_headers existed would otherwise lose
     *  kimi's UA, which its /coding endpoint 403s on. */
    fun staticFor(providerCfg: ProviderConfig): Map<String, String> = base + providerCfg.staticHeaders
}

/**
 * The anthropic-passthrough construction site: the dialect's ONE provider fed effective quirks and
 * headers selected by assembly then overlaid by TOML, plus optional Kimi runtime identity.
 */
internal class PassthroughAssembly {
    private val quirksOverlay = QuirksOverlay()

    /** The dialect's ONE provider, fed assembly-selected data: TOML quirks overlaid on the head's
     *  base profile, provider-default headers overridden by TOML, and (Kimi only) device identity. */
    internal fun passthroughProviderFor(
        ctx: ProviderBuild,
        label: String,
        auth: RefreshableAuthProvider,
        base: PassthroughQuirks,
        headers: PassthroughHeaders = PassthroughHeaders(),
    ): Provider = PassthroughProvider(
        tuning = ProviderTuning(
            name = ProviderName(key = ctx.key, label = label),
            catalog = ctx.catalog,
            pinnedModel = ctx.head.pinnedModel,
            auth = auth,
            locations = ProviderLocations(baseUrl = ctx.providerCfg.baseUrl),
            watchdog = ctx.faultPlan.watchdog,
            loginCommand = ctx.faultPlan.loginCommand,
        ),
        quirks = quirksOverlay.passthroughQuirks(ctx.providerCfg, base),
        staticHeaders = headers.staticFor(ctx.providerCfg),
        identityHeaders = headers.identity,
        // PT-002/v27: same session-stable effort proxy ResponsesProvider threads as configEffort.
        configEffort = ctx.cfg.effort,
    )
}
