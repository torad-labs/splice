// NEW: chatgpt-oauth legacy HUD knobs (port / pinnedModel / chatgptApiBase).
// Audit, Nov 9, 2026: codexAuthPath no longer moves the credential file. It overwrote the provider's auth.file here, so
// the daemon read one file while `splice login` and status, which never see a knob, wrote and read another. The file
// is AuthConfig.loginFile everywhere.
package splice.provider.codex

import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig

public class CodexLegacyKnobs {
    public fun remapHead(head: HeadConfig, port: Int, pinnedModel: String): HeadConfig =
        head.copy(port = port, pinnedModel = pinnedModel)

    public fun remapProvider(provider: ProviderConfig, baseUrl: String): ProviderConfig =
        provider.copy(baseUrl = baseUrl)
}
