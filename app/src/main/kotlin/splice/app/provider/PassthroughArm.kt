// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.passthroughProvider) @ ed5c868 — client-auth holds
// no credential; every unregistered API-key/custom vendor takes the neutral profile. Kimi lives in
// KimiPassthroughArm. ProviderAssembly rejects registered incompatible kinds first.
package splice.app.provider

import splice.app.daemon.TopologyLoader
import splice.core.auth.ClientAuthProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.provider.openai.ApiKeyAuthProvider
import java.nio.file.Paths

internal class PassthroughArm(
    private val passthroughAssembly: PassthroughAssembly,
) {
    // CLIENT is always neutral: no Moonshot deformations, headers, or device identity. Unregistered
    // API-key/custom vendors start the same way and declare facts in TOML.
    internal fun passthroughProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        if (providerCfg.auth.kind == CLIENT) {
            val auth = ClientAuthProvider(key)
            return Wired(
                passthroughAssembly.passthroughProviderFor(
                    ctx,
                    label,
                    auth,
                    PassthroughQuirks(providerTag = key),
                ),
                auth,
            )
        }
        val auth = ApiKeyAuthProvider(
            envVar = providerCfg.auth.effectiveApiKeyEnv(key),
            keyFile = providerCfg.auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
        )
        return Wired(
            passthroughAssembly.passthroughProviderFor(
                ctx = ctx,
                label = label,
                auth = auth,
                base = PassthroughQuirks(providerTag = key),
            ),
            auth,
        )
    }
}
