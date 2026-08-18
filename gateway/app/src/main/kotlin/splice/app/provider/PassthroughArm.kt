// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.passthroughProvider, KIMI_BASE_HEADERS) @
// ed5c868 — invariants unchanged: anthropic-passthrough dispatch: client-auth (no credential held)
// vs kimi-oauth (device flow, x-api-key, proactive refresh) vs any other kind (ApiKeyAuthProvider
// -> Bearer, correct for Moonshot's anthropic pay-per-token base). All three build the SAME generic
// PassthroughProvider; what differs is DATA — the auth, the base quirk profile, and whether a
// computed device identity rides along.
package splice.app.provider

import splice.app.TopologyLoader
import splice.core.auth.ClientAuthProvider
import splice.core.config.StatePaths
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughQuirksDefaults
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.openai.ApiKeyAuthProvider
import java.nio.file.Paths

/** Kimi's static vendor headers as they were hardcoded before the TOML surface existed: its
 *  /coding endpoint 403s an unrecognized UA, and the Anthropic wire needs its version on every
 *  request. Kept as the kimi arms' BASE (the example TOML declares the same values as
 *  documentation) so an operator who never edited splice.toml keeps a working head. */
private val KIMI_BASE_HEADERS = mapOf(
    "anthropic-version" to "2023-06-01",
    "User-Agent" to "KimiCLI/1.5",
)

internal class PassthroughArm(
    private val statePaths: StatePaths,
    private val passthroughAssembly: PassthroughAssembly,
    private val kimiOAuth: KimiOAuth,
) {
    // anthropic-passthrough dispatch: kimi-oauth (device flow, x-api-key, proactive refresh) vs any
    // other kind (ApiKeyAuthProvider → Bearer, correct for Moonshot's anthropic pay-per-token base).
    // Both build the SAME generic PassthroughProvider; what differs is DATA — the auth, the base
    // quirk profile, and whether a computed device identity rides along.
    //
    // The base profile is what a pre-campaign splice.toml relies on: a kimi-oauth head bases on
    // Kimi's deformation set, so an operator who never declared the new quirks keeps working, while
    // any knob their TOML DOES set still overrides. The api-key arm bases on Kimi's set too, because
    // that arm exists for Moonshot's own anthropic endpoint (the pay-per-token twin of the OAuth
    // head) — an unrelated anthropic-compatible vendor declares what it needs in TOML.
    internal fun passthroughProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        // A client-auth head holds NO credential and declares its vendor facts in TOML, so it takes
        // the NEUTRAL base: no Kimi deformations, no Kimi headers, no device identity.
        if (providerCfg.auth.kind == CLIENT) {
            val auth = ClientAuthProvider(key)
            return Wired(
                passthroughAssembly.passthroughProviderFor(ctx, label, auth, PassthroughQuirks(providerTag = key)),
                auth,
            )
        }
        val (auth, identity) = when (providerCfg.auth.kind) {
            KIMI_OAUTH -> kimiOAuth.kimiOauthAuth(ctx)
            else -> {
                val apiKey = ApiKeyAuthProvider(
                    envVar = providerCfg.auth.effectiveApiKeyEnv(key),
                    keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
                )
                apiKey to KimiDeviceIdentity(deviceIdPath = statePaths.stateDir.resolve("$key-device_id"))
            }
        }
        return Wired(
            passthroughAssembly.passthroughProviderFor(
                ctx = ctx,
                label = label,
                auth = auth,
                base = PassthroughQuirksDefaults().kimi(key),
                baseHeaders = KIMI_BASE_HEADERS,
                identityHeaders = identity::headers,
            ),
            auth,
        )
    }
}
