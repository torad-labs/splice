package splice.core.topology

/** A provider's own serving selection, shared by boot validation and gateway assembly. It binds no port. */
public class GatewayProviderRoute(key: String, private val provider: ProviderConfig) {
    public val assembly: HeadConfig = HeadConfig(
        provider = key,
        port = 0,
        discoveryPrefix = GatewaySelectorPrefixes.canonical(key),
        pinnedModel = provider.pinnedModel ?: provider.models.firstOrNull()?.id.orEmpty(),
        modelSlots = provider.modelSlots,
        contextWindow = provider.contextWindow,
        systemPrompt = provider.systemPrompt,
        systemPromptFile = provider.systemPromptFile,
        systemPromptMode = provider.systemPromptMode,
        servedByGateway = true,
    )

    /** Only authored model rows are known before discovery. The runtime uses the same model selection. */
    internal fun rosterRefusal(): String? =
        (HeadModels(provider).select(assembly, provider.models) as? HeadModels.Roster.Refused)?.detail
}
