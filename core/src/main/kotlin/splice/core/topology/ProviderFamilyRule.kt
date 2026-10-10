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
 * table of its own: the registered auth kind (a Codex sign-in is OpenAI), then a provider the operator
 * marked `local`, then the api-key registry by provider key (the lookup that already picks GrokProvider
 * for `xai`), and only then the local-runtime rule (an openai-chat base URL on loopback). Oct 10, 2026:
 * the loopback rule ran before the registry, so OpenRouter pointed at a proxy on this machine grouped as
 * "This computer", with no key card and no budget (Marlin's Accounts walk, p153 and p154). A
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
            null -> keyed(key, provider)
        }

    /** A provider with no sign-in of its own: the operator's `local`, then its key in the registry, then loopback. */
    private fun keyed(key: String, provider: ProviderConfig): String? =
        if (provider.local == true) {
            LOCAL_FAMILY
        } else {
            ApiKeyProviderRegistry.row(key)?.id ?: LOCAL_FAMILY.takeIf { provider.isLocal }
        }
}
