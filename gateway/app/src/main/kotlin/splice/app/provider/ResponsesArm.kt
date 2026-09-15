// PORT-OF: splice/app/Daemon.kt (ProviderAssembly.responsesProvider) @ ed5c868. ProviderAssembly
// rejects registered incompatible kinds first; this arm dispatches ChatGPT/Grok OAuth and sends
// unregistered api-key/custom kinds to ApiKeyResponsesArm.
package splice.app.provider

internal class ResponsesArm(
    private val grokResponsesArm: GrokResponsesArm,
    private val apiKeyResponsesArm: ApiKeyResponsesArm,
    private val codexResponsesArm: CodexResponsesArm,
) {
    internal fun responsesProvider(ctx: ProviderBuild, label: String): Wired = when (ctx.providerCfg.auth.kind) {
        CHATGPT_OAUTH -> codexResponsesArm.codexOAuthProvider(ctx, label)
        GROK_OAUTH -> grokResponsesArm.grokOAuthProvider(ctx, label)
        else -> apiKeyResponsesArm.apiKeyResponsesProvider(ctx, label)
    }
}
