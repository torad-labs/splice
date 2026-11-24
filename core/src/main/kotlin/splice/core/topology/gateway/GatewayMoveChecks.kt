// NEW: gateway-no-heads. A value only a head block holds, and that would make the gateway serve that head's provider
// differently once the head goes (an older selector prefix, a window, the tiers' models, the standing prompt, an
// override), is a WARNING that names it and says where it moves to (GatewayMove); `splice check-config --fix` offers the
// move. upgrade-compat (Nov 23, 2026): these were boot findings, so every 0.6.4 splice.toml that said them on its head,
// as 0.6.4 asked, refused to boot and to upgrade on 0.7.0. The gateway now serves what the heads on a provider agree on
// (HeadFallbacks), so such a file boots and serves as before, and the warning says whether the gateway has the value
// today. A head whose values the provider already carries the same way is no warning; a pin is where the head's own
// command starts, and capture and console knobs stay with the head. The values a provider carries for the gateway are
// checked here too, at the provider's own path, and those stay boot findings: the gateway cannot serve them at all.
package splice.core.topology.gateway

import splice.core.config.Knob
import splice.core.config.providerCarriedKnobs
import splice.core.prompt.SystemPromptMode
import splice.core.topology.GatewayMove
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.toml.TomlKeys

internal class GatewayMoveChecks(private val topology: Topology) {
    private val keys = TomlKeys()

    /** Each provider as the gateway serves it today, with what its heads agree on. */
    private val served = HeadFallbacks(topology).providers()

    /** What refuses boot: a provider value the gateway cannot serve. */
    fun findings(): List<TopologyFinding> = providers()

    /** What boots and is said: a value a head holds that its provider should hold once the head goes. */
    fun warnings(): List<TopologyFinding> = topology.heads.flatMap { (key, head) ->
        topology.providers[head.provider]?.let { provider -> of(key, head, provider) }.orEmpty()
    }

    /** The values a provider carries for the gateway, each checked at its own path: a window the catalog could not serve
     *  and a standing prompt named twice would refuse the provider when the gateway builds it. */
    private fun providers(): List<TopologyFinding> = topology.providers.flatMap { (key, provider) ->
        listOfNotNull(
            TopologyFinding("providers.$key.context_window", "must be positive")
                .takeIf { (provider.contextWindow ?: 1L) <= 0L },
            TopologyFinding("providers.$key.system_prompt", "set system_prompt or system_prompt_file, not both")
                .takeIf { provider.systemPrompt != null && provider.systemPromptFile != null },
        )
    }

    private fun of(key: String, head: HeadConfig, provider: ProviderConfig): List<TopologyFinding> =
        prefix(key, head) + serving(key, head, provider) + overrides(key, head, provider)

    /** The head's selector prefix, which the gateway reads from the head while it is there (SelectorAliases), and from
     *  `[door.selector_aliases]` once it is gone. */
    private fun prefix(key: String, head: HeadConfig): List<TopologyFinding> {
        val said = head.discoveryPrefix
        if (said.isBlank()) return emptyList()
        val explicit = topology.door.selectorPrefixes[said]
        val canonical = topology.providers.keys.firstOrNull { GatewaySelectorPrefixes.canonical(it) == said }
        val routed = explicit ?: canonical ?: GatewaySelectorPrefixes.legacy[said]
        if (routed == head.provider) return emptyList()
        val move = GatewayMove(
            table = "door.selector_aliases",
            key = keys.render(said),
            value = quoted(head.provider),
            keepsSource = true,
        ).takeIf { explicit == null && canonical == null }
        val why = "the gateway reads this prefix from this head while it is in splice.toml, and only from " +
            "[door.selector_aliases] once it goes; the head keeps it for its command"
        return listOf(TopologyFinding("heads.$key.discovery_prefix", why, move = move))
    }

    /** The head's window, tiers and standing prompt, each against the provider's own and what the gateway serves. */
    private fun serving(key: String, head: HeadConfig, provider: ProviderConfig): List<TopologyFinding> {
        val table = "providers.${keys.render(head.provider)}"
        val gets = served.getValue(head.provider)
        return listOfNotNull(
            held(key, Said("context_window", head.contextWindow, provider.contextWindow, gets.contextWindow), table),
            held(
                key,
                Said(
                    "model_slots",
                    head.modelSlots.ifEmpty { null },
                    provider.modelSlots.ifEmpty { null },
                    gets.modelSlots.ifEmpty { null },
                ),
                table,
            ),
            embeddedTiers(key, head, provider),
        ) + prompt(key, head, provider)
    }

    /** The head's standing prompt, text, file and mode, which the gateway reads as one value (HeadFallbacks): each key
     *  the head says other than its provider is a warning, and they move to the provider together, and only when every
     *  one of them the provider says is the head's own and every head on the provider is served the same prompt. Moved
     *  one at a time, the key left behind would no longer be served and turn into a new warning; moved beside a prompt
     *  the provider wrote, they would make a third prompt; moved while another head is served another, that head would
     *  read the moved keys where it says none (a mode it leaves to `append` would turn `replace`). */
    private fun prompt(key: String, head: HeadConfig, provider: ProviderConfig): List<TopologyFinding> {
        val table = "providers.${keys.render(head.provider)}"
        val gets = served.getValue(head.provider)
        val trio = listOf(
            Said("system_prompt", head.systemPrompt, provider.systemPrompt, gets.systemPrompt),
            Said("system_prompt_file", head.systemPromptFile, provider.systemPromptFile, gets.systemPromptFile),
            Said("system_prompt_mode", head.systemPromptMode, provider.systemPromptMode, gets.systemPromptMode),
        )
        val own = trio.all { it.provider == null || it.provider == it.head }
        val shared = topology.heads.values.filter { it.provider == head.provider }
            .map { servedPrompt(it, provider) }.distinct().size == 1
        val apart = trio.filter { it.head != null && it.head != it.provider }
        val why = if (own && !shared) {
            "another head on this provider is served another standing prompt, which a move to [$table] would " +
                "change; set the one the gateway should serve in [$table], and on each head the one it should keep"
        } else {
            null
        }
        return apart.mapNotNull { said ->
            held(key, said, table, Together(own && shared, apart.map(Said<*>::field) - said.field, why))
        }
    }

    /** The standing prompt [head] is served on [provider], text, file and mode, an unsaid mode being `append`
     *  (HeadConfig.systemPromptFor). */
    private fun servedPrompt(head: HeadConfig, provider: ProviderConfig): Prompt = head.servedOn(provider).let {
        Prompt(it.systemPrompt, it.systemPromptFile, it.systemPromptMode ?: SystemPromptMode.APPEND)
    }

    /** The tiers a head names on its own model rows (`slot`), which the gateway reads from the provider's `model_slots`
     *  instead. They move as that map and the head keeps its allowlist and its slots as they are. */
    private fun embeddedTiers(key: String, head: HeadConfig, provider: ProviderConfig): TopologyFinding? {
        val table = "providers.${keys.render(head.provider)}"
        val said = head.tierModels()
        if (head.models.orEmpty().none { it.slot != null } || said == provider.tierModels) return null
        val free = provider.modelSlots.isEmpty()
        val map = said.entries.joinToString(", ", "{ ", " }") { (slot, id) -> "${keys.render(slot)} = ${quoted(id)}" }
        val move = GatewayMove(table, "model_slots", map, keepsSource = true).takeIf { free }
        val why = unserved(free, inherited = served[head.provider]?.tierModels == said, table)
        return TopologyFinding("heads.$key.models", why, move = move)
    }

    /** A warning when the head says [said]'s field and its provider does not say the same. It moves to the provider's
     *  table when the provider says nothing; when the provider says something else, the person chooses which stands.
     *  [together] is what moves with it, when it is one value with other keys of the head. */
    private fun held(
        head: String,
        said: Said<*>,
        table: String,
        together: Together = Together(said.provider == null),
    ): TopologyFinding? {
        if (said.head == null || said.head == said.provider) return null
        val free = together.free
        val move = GatewayMove(table, said.field, value = null, keepsSource = false, together.companions)
            .takeIf { free }
        val why = together.why ?: unserved(free, inherited = said.gateway == said.head, table)
        return TopologyFinding("heads.$head.${said.field}", why, move = move)
    }

    /** [text] as a TOML string, which a value is and a key need not be. */
    private fun quoted(text: String): String = "\"${keys.escape(text)}\""

    /** Whether [knob] is a warning of this check: one a provider carries (providerCarriedKnobs). A capture or console
     *  knob stays with a head, and a daemon-wide one a head already cannot set, whose own finding offers the move for the
     *  serving bounds a provider may carry (TopologyChecks.knobs). */
    private fun servedByProvider(knob: Knob): Boolean = knob in providerCarriedKnobs

    /** Why a head's value is a warning: what the gateway serves today, and what it would serve once the head goes. */
    private fun unserved(free: Boolean, inherited: Boolean, table: String): String = when {
        !free -> "[$table] says another, which the gateway serves; the head keeps its own for its command"
        inherited ->
            "only this head says it; the gateway serves the provider with it while the head is in " +
                "splice.toml, and without it once the head goes, so move it to [$table]"
        else ->
            "the heads on this provider say different values, so the gateway serves the provider with none; " +
                "set the one it should serve in [$table]"
    }

    /** Each override the gateway reads that the head sets and its provider does not say the same. */
    private fun overrides(key: String, head: HeadConfig, provider: ProviderConfig): List<TopologyFinding> =
        head.overrides.mapNotNull { (knob, value) ->
            val known = Knob.entries.firstOrNull { it.key == knob }?.takeIf(::servedByProvider)
            if (known == null || provider.overrides[knob] == value) return@mapNotNull null
            val free = knob !in provider.overrides
            val table = "providers.${keys.render(head.provider)}.overrides"
            val move = GatewayMove(table, knob, value = null, keepsSource = false).takeIf { free }
            val why = unserved(free, inherited = served[head.provider]?.overrides?.get(knob) == value, table)
            TopologyFinding("heads.$key.overrides.$knob", why, move = move)
        }

    /** One value as a head says it ([head]), as its provider says it ([provider]) and as the gateway serves it
     *  today ([gateway]). */
    private data class Said<T : Any>(val field: String, val head: T?, val provider: T?, val gateway: T?)

    /** A standing prompt as a head is served it. */
    private data class Prompt(val text: String?, val file: String?, val mode: SystemPromptMode)

    /** Whether a value is [free] to move, its provider saying none of it, the head's other keys that move with it
     *  ([companions]), and the reason it is said when that is not the one its value alone gives ([why]). */
    private data class Together(val free: Boolean, val companions: List<String> = emptyList(), val why: String? = null)
}
