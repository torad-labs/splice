// PORT-OF: splice/app/Daemon.kt (PassthroughAssembly.passthroughProviderFor) @ ed5c868 —
// the anthropic-passthrough construction site — the dialect's ONE provider fed assembly-selected
// base data plus the TOML quirk/header overlays (now QuirksOverlay.passthroughQuirks).
package splice.app.provider

import splice.core.auth.RefreshableAuthProvider
import splice.dialect.passthrough.IdentityHeaders
import splice.dialect.passthrough.PassthroughProvider
import splice.dialect.passthrough.PassthroughQuirks
import splice.spi.Provider
import splice.spi.ProviderTuning

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
        baseHeaders: Map<String, String> = emptyMap(),
        identityHeaders: IdentityHeaders = IdentityHeaders { emptyMap() },
    ): Provider = PassthroughProvider(
        tuning = ProviderTuning(
            key = ctx.key,
            label = label,
            catalog = ctx.catalog,
            pinnedModel = ctx.head.pinnedModel,
            auth = auth,
            baseUrl = ctx.providerCfg.baseUrl,
            watchdog = ctx.watchdog,
            loginCommand = ctx.loginCommand,
        ),
        quirks = quirksOverlay.passthroughQuirks(ctx.providerCfg, base),
        // Base FIRST so an operator's TOML overrides it, and absent TOML keeps the head serving: these
        // headers used to be hardcoded in the provider, so a splice.toml written before extra_headers
        // existed would otherwise lose kimi's UA — which its /coding endpoint 403s on.
        staticHeaders = baseHeaders + ctx.providerCfg.staticHeaders,
        identityHeaders = identityHeaders,
        // PT-002/v27: same session-stable effort proxy ResponsesProvider threads as configEffort.
        configEffort = ctx.cfg.effort,
    )
}
