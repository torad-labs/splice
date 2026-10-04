// NEW: 2026-09-25 (console ruling 4, item 6) — which vendor a provider belongs to, so the console
// colours a head by its family and not by where it sits in the registry. Keyed by the PROVIDER, never
// the wire: claude-deepseek speaks the Anthropic dialect and is DeepSeek, and three bonsai providers
// on the operator's own machine are one family, local.
package splice.core.topology

// why: the family of a runtime on the operator's own machine, the one family no registry row names.
private const val LOCAL_FAMILY = "local"

/**
 * The family a provider belongs to, or null when splice cannot name one.
 *
 * It reads only what the daemon already relies on to TALK to the provider, so it adds no vendor
 * table of its own: the registered auth kind (a Codex sign-in is OpenAI), then the local-runtime
 * rule (an openai-chat base URL on loopback is the operator's own process, whatever its key), then
 * the api-key registry by provider key, the lookup that already picks GrokProvider for `xai`. A
 * provider none of those names is null, and the console falls back to registry order for it.
 */
public class ProviderFamilyRule {
    public fun of(key: String, provider: ProviderConfig): String? =
        when (AuthKindRegistry.from(provider.auth.kind)) {
            AuthKind.ChatgptOAuth -> "openai"
            AuthKind.GrokOAuth -> "xai"
            AuthKind.KimiOAuth -> "moonshot"
            AuthKind.MuseOAuth -> "meta"
            AuthKind.Client -> "anthropic"
            null -> if (provider.isLocal) LOCAL_FAMILY else ApiKeyProviderRegistry.row(key)?.id
        }
}
