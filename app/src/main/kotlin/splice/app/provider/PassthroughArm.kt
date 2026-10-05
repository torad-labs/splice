// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.passthroughProvider) @ ed5c868 — client-auth holds
// no credential; every unregistered API-key/custom vendor takes the neutral profile. Kimi lives in
// KimiPassthroughArm. ProviderAssembly rejects registered incompatible kinds first.
package splice.app.provider

import splice.core.auth.ClientAuthProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.provider.openai.ApiKeyAuthProvider
import splice.topology.TopologyLoader
import java.nio.file.Paths

internal class PassthroughArm(
    private val passthroughAssembly: PassthroughAssembly,
    private val claudeAccounts: ClaudeAccountWiring,
    private val changes: splice.app.auth.claude.ClaudePoolChanges = splice.app.auth.claude.ClaudePoolChanges(),
) {
    // CLIENT uses Anthropic's signature verification and eager custom-tool input streaming.
    // It has no Moonshot deformations, headers, or device identity. Unregistered API-key/custom
    // vendors start neutral and declare facts in TOML.
    internal fun passthroughProvider(ctx: ProviderBuild, label: String): Wired {
        val key = ctx.key
        val providerCfg = ctx.providerCfg
        if (providerCfg.auth.kind == CLIENT) {
            val auth = ClientAuthProvider(key)
            val accounts = claudeAccounts.accounts(key, auth)
            return Wired(
                passthroughAssembly.passthroughProviderFor(
                    ctx,
                    label,
                    auth,
                    PassthroughQuirks(
                        providerTag = key,
                        verifiesThinkingSignatures = true,
                        eagerToolInputs = true,
                    ),
                ),
                auth,
                // Every account this command holds beyond the caller's own Claude Code sign-in (operator ruling,
                // Oct 3, 11:44 PM CT). Empty on a command nobody has added one to, which keeps the pre-pool path.
                accounts,
                changes.view(key, accounts),
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
