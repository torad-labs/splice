// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.passthroughProvider) @ ed5c868 — client-auth holds
// no credential; every unregistered API-key/custom vendor takes the neutral profile. Kimi lives in
// KimiPassthroughArm. ProviderAssembly rejects registered incompatible kinds first.
package splice.app.provider

import splice.core.auth.ClientAuthProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.dialect.anthropic.PassthroughRequestQuirks
import splice.dialect.anthropic.PassthroughThinkingQuirks

internal class PassthroughArm(
    private val passthroughAssembly: PassthroughAssembly,
    private val claudeAccounts: ClaudeAccountWiring,
    private val changes: splice.app.auth.claude.ClaudePoolChanges = splice.app.auth.claude.ClaudePoolChanges(),
) {
    // Unregistered API-key vendors read the standard. Registered providers can name their own refusals.
    private val claudeAssembly = PassthroughAssembly(HeadReplies.claude)
    private val deepSeekAssembly = PassthroughAssembly(HeadReplies.deepSeek)

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
                claudeAssembly.passthroughProviderFor(
                    ctx,
                    label,
                    auth,
                    PassthroughQuirks(
                        providerTag = key,
                        thinking = PassthroughThinkingQuirks(
                            verifiesThinkingSignatures = true,
                        ),
                        request = PassthroughRequestQuirks(
                            eagerToolInputs = true,
                        ),
                    ),
                ),
                auth,
                // Every account this command holds beyond the caller's own Claude Code sign-in. Empty on a command
                // nobody has added one to, which keeps the pre-pool path.
                accounts,
                changes.view(key, accounts),
            )
        }
        val auth = KeyAuth().of(ctx)
        val assembly = if (ctx.head.provider == "deepseek") deepSeekAssembly else passthroughAssembly
        return Wired(
            assembly.passthroughProviderFor(
                ctx = ctx,
                label = label,
                auth = auth,
                base = PassthroughQuirks(providerTag = key),
            ),
            auth,
        )
    }
}
