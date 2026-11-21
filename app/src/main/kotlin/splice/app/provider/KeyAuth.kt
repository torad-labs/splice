// NEW: custom-provider (issue #680, cp-credential). The credential a head on a key sends, one answer for every
// dialect's arm: its provider's credential command when its auth kind is "command" (shared by every head of the
// provider), else the api-key it reads from its environment, key file or splice's key store. The key rides where an
// api-key's rides.
package splice.app.provider

import splice.core.auth.CommandCredentials
import splice.core.auth.RefreshableAuthProvider
import splice.provider.openai.ApiKeyAuthProvider
import splice.topology.TopologyLoader
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Paths

internal class KeyAuth {
    fun of(ctx: ProviderBuild): RefreshableAuthProvider {
        val auth = ctx.providerCfg.auth
        val command = auth.credentialCommand?.takeIf { auth.isCommand }
        if (command != null) {
            return CommandCredentials.of(ctx.head.provider, command, CredentialCommands(ProcessDispatchers().io()))
        }
        return ApiKeyAuthProvider(
            envVar = auth.effectiveApiKeyEnv(ctx.key),
            keyFile = auth.file?.let { Paths.get(TopologyLoader.expandHome(it)) },
        )
    }
}
