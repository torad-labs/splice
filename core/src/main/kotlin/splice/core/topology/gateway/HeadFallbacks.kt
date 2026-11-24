// NEW: upgrade-compat (v0.6.4 -> v0.7.0). A splice.toml written for 0.6.4 says a provider's window, tiers, standing
// prompt and serving knobs on the `[heads.<key>]` that runs it, since a head was the only thing that served a provider
// then. The gateway that 0.7.0 adds serves the provider from `[providers.<key>]` alone, so the same file would serve the
// door differently from the head it replaced, or (before this) refuse to boot at all. Here the gateway reads what the
// provider leaves unsaid from the heads on it, when they agree: the file boots and serves as it did, and the warning
// GatewayMoveChecks gives still offers the move to the provider's table, which is where the value lives once the head
// goes. Heads read none of this: a head on the same provider that says nothing keeps serving as it did.
package splice.core.topology.gateway

import splice.core.config.Knob
import splice.core.config.providerCarriedKnobs
import splice.core.prompt.SystemPromptMode
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology

/** Each provider of [topology] as the gateway serves it: its own values, and where it says nothing, the value every head
 *  on it that says one agrees on. Heads that disagree leave the provider's own (absent) value, which the warning names. */
public class HeadFallbacks(private val topology: Topology) {
    /** Every provider, filled. */
    public fun providers(): Map<String, ProviderConfig> =
        topology.providers.mapValues { (key, provider) -> filled(key, provider) }

    /** [key]'s provider filled, or [provider] itself when no head runs it. */
    public fun filled(key: String, provider: ProviderConfig): ProviderConfig {
        val heads = topology.heads.values.filter { it.provider == key }
        if (heads.isEmpty()) return provider
        val prompt = prompt(provider, heads)
        return provider.copy(
            contextWindow = provider.contextWindow ?: agreed(heads.mapNotNull { it.contextWindow }),
            modelSlots = provider.modelSlots.ifEmpty {
                agreed(heads.map(HeadConfig::tierModels).filter { it.isNotEmpty() }).orEmpty()
            },
            systemPrompt = prompt.text,
            systemPromptFile = prompt.file,
            systemPromptMode = prompt.mode,
            overrides = overrides(heads) + provider.overrides,
        )
    }

    /** The standing prompt as one value, text, file and mode together, so a head's `replace` never lands on a prompt the
     *  provider wrote, and a provider that says any of the three keeps all three of its own. */
    private fun prompt(provider: ProviderConfig, heads: List<HeadConfig>): Prompt {
        val own = Prompt(provider.systemPrompt, provider.systemPromptFile, provider.systemPromptMode)
        if (own.said) return own
        val said = heads.map { Prompt(it.systemPrompt, it.systemPromptFile, it.systemPromptMode) }.filter(Prompt::said)
        return agreed(said) ?: own
    }

    /** The knobs a provider carries (providerCarriedKnobs) that the heads on it set, each where every head that sets it
     *  agrees. A head-only, display or daemon-wide knob stays with its head. */
    private fun overrides(heads: List<HeadConfig>): Map<String, String> {
        val carried = providerCarriedKnobs.mapTo(HashSet(), Knob::key)
        return heads.flatMap { it.overrides.keys }.filter { it in carried }.distinct().mapNotNull { knob ->
            agreed(heads.mapNotNull { it.overrides[knob] })?.let { knob to it }
        }.toMap()
    }

    /** The one value [said] holds, or null when it is empty or holds two. */
    private fun <T> agreed(said: List<T>): T? = said.distinct().singleOrNull()

    /** A standing prompt as a table writes it. */
    private data class Prompt(val text: String?, val file: String?, val mode: SystemPromptMode?) {
        val said: Boolean get() = text != null || file != null || mode != null
    }
}
