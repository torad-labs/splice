// NEW: gateway-no-heads (.git/campaign/gateway-no-heads.json). The gateway's selectors, and the old ones read as the
// provider's own. The gateway names each provider's model `claude-<provider>--<model id>`. A selector a caller already
// holds under an old head's prefix (`claude-codex--gpt-6.2-sol`, `claude-grok--grok-4.7`) keeps working, because the
// gateway owns the table that maps an old prefix to the provider it stood for. The table does not depend on a head
// existing, since removing every `[heads.*]` block must leave every selector answering: it is the gateway's built-in
// legacy prefixes, the person's explicit `[door.selector_aliases]` for a custom prefix and, only where a head block is
// still there, its `discovery_prefix` too, read once at boot. No turn reads a head, and none is relayed to one.
package splice.app.desktop

import splice.core.topology.HeadConfig

/** An old or current selector read as the provider and the provider's own model id it names. */
internal data class AliasedSelector(val provider: String, val model: String)

/**
 * [providers] are the providers splice.toml declares: every selector resolves to one of them or to nothing. Where two
 * sources claim a prefix the earlier wins: the person's [explicit] aliases, then the current `claude-<provider>--`,
 * then a still-present head's prefix, then the gateway's built-in legacy ones.
 */
internal class SelectorAliases(
    providers: Collection<String>,
    explicit: Map<String, String> = emptyMap(),
    heads: Collection<HeadConfig> = emptyList(),
) {
    private val declared: Set<String> = providers.toSet()

    // The longest prefix first, so one that begins another (`claude-x--` and `claude-x-mini--`) is never taken for it.
    private val prefixes: List<Pair<String, String>> = (
        explicit.toList() +
            declared.map { current(it) to it } +
            heads.filter { it.discoveryPrefix.isNotEmpty() }.map { it.discoveryPrefix to it.provider } +
            LEGACY_PREFIXES.toList()
        )
        .filter { (prefix, provider) -> prefix.isNotEmpty() && provider in declared }
        .distinctBy { it.first }
        .sortedByDescending { it.first.length }

    /** The provider and model id [selector] names, under the current naming or an old prefix; null when it names none
     *  of splice.toml's providers. */
    fun of(selector: String): AliasedSelector? = prefixes.firstOrNull { (prefix, _) -> selector.startsWith(prefix) }
        ?.let { (prefix, provider) -> AliasedSelector(provider, selector.removePrefix(prefix)) }
        ?.takeIf { it.model.isNotEmpty() }

    /** [selector] as the gateway names it now, or [selector] itself when it is under no prefix this table knows. */
    fun canonical(selector: String): String = of(selector)?.let { current(it.provider) + it.model } ?: selector

    private fun current(provider: String): String = "$SELECTOR_START$provider$SELECTOR_END"
}

/** How the gateway's selectors begin and where the provider's name ends: `claude-<provider>--<model id>`. */
private const val SELECTOR_START = "claude-"
private const val SELECTOR_END = "--"

/** The prefixes the heads of every starter splice.toml carried, each with the provider it served: the selectors callers
 *  hold today. A head whose provider is not declared makes its prefix no alias. */
private val LEGACY_PREFIXES: Map<String, String> = mapOf(
    "claude-codex--" to "codex",
    "claude-grok--" to "xai",
    "claude-kimi--" to "kimi",
    "claude-muse--" to "muse",
    "claude-openrouter--" to "openrouter",
    "claude-fireworks--" to "fireworks",
    "claude-deepseek--" to "deepseek",
    "claude--" to "anthropic",
)
