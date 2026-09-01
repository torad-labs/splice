// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.passthroughProvider, KIMI_BASE_HEADERS) @
// ed5c868 — client-auth holds no credential; Kimi OAuth/API-key keeps its Moonshot deformations;
// every other unregistered API-key/custom vendor takes the neutral profile. ProviderAssembly rejects
// registered incompatible kinds first. Auth kind selects the credential; provider ID selects Kimi's
// base quirks, static headers, and device identity.
package splice.app.provider

import splice.app.TopologyLoader
import splice.core.auth.ClientAuthProvider
import splice.core.config.StatePaths
import splice.dialect.passthrough.IdentityHeaders
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
    // The provider key, not the auth mechanism, owns vendor deformations. Kimi keeps its base
    // quirks, static headers, and computed X-Msh identity under both OAuth and API-key auth, so a
    // pre-campaign kimi head remains byte-identical. On every non-Kimi provider ID, unregistered
    // API-key/custom fallback starts neutral and declares vendor facts in TOML; otherwise it would
    // silently impersonate Moonshot on an unrelated anthropic-compatible upstream.
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
        val kimiProvider = ctx.head.provider == "kimi"
        val (auth, identity) = when (providerCfg.auth.kind) {
            KIMI_OAUTH -> kimiOAuth.kimiOauthAuth(ctx)
            else -> {
                val apiKey = ApiKeyAuthProvider(
                    envVar = providerCfg.auth.effectiveApiKeyEnv(key),
                    keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
                )
                val kimiIdentity = if (kimiProvider) {
                    KimiDeviceIdentity(deviceIdPath = statePaths.stateDir.resolve("$key-device_id"))
                } else {
                    null
                }
                apiKey to kimiIdentity
            }
        }
        return Wired(
            passthroughAssembly.passthroughProviderFor(
                ctx = ctx,
                label = label,
                auth = auth,
                base = if (kimiProvider) {
                    PassthroughQuirksDefaults().kimi(key)
                } else {
                    PassthroughQuirks(providerTag = key)
                },
                baseHeaders = if (kimiProvider) KIMI_BASE_HEADERS else emptyMap(),
                identityHeaders = if (kimiProvider) {
                    IdentityHeaders(requireNotNull(identity)::headers)
                } else {
                    IdentityHeaders { emptyMap() }
                },
            ),
            auth,
        )
    }
}
