// NEW: chatgpt-oauth legacy HUD knobs (port / pinnedModel / chatgptApiBase / codexAuthPath).
package splice.provider.codex

import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig

public class CodexLegacyKnobs {
    public fun remapHead(head: HeadConfig, port: Int, pinnedModel: String): HeadConfig =
        head.copy(port = port, pinnedModel = pinnedModel)

    public fun remapProvider(provider: ProviderConfig, baseUrl: String, authFile: String): ProviderConfig =
        provider.copy(baseUrl = baseUrl, auth = provider.auth.copy(file = authFile))
}
