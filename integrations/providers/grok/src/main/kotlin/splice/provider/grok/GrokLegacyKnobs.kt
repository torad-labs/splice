// NEW: grok-oauth legacy HUD knobs (grokPort / grokModel / xaiApiBase). grokAuthPath is not
// remapped here — GrokResponsesArm reads providerCfg.auth.file, else cfg.grokAuthPath.
package splice.provider.grok

import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig

public class GrokLegacyKnobs {
    public fun remapHead(head: HeadConfig, port: Int, pinnedModel: String): HeadConfig =
        head.copy(port = port, pinnedModel = pinnedModel)

    public fun remapProvider(provider: ProviderConfig, baseUrl: String): ProviderConfig =
        provider.copy(baseUrl = baseUrl)
}
