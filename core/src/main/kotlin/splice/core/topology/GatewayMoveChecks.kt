// NEW: gateway-no-heads. A value only a head block holds, and that would make the gateway serve that head's provider
// differently, is a boot finding that names it: the gateway serves a provider with no head, from the provider's own
// table, so what a head alone says (an older selector prefix, a window, the tiers' models, the standing prompt, an
// override) would be lost the day the head goes. Each finding says where the value moves to (GatewayMove), and the fix
// session offers the move. A head whose values the provider already carries the same way is no finding; a pin is where
// the head's own command starts, and capture and console knobs stay with the head. The values a provider carries for
// the gateway are checked here too, at the provider's own path.
package splice.core.topology

import splice.core.config.Knob
import splice.core.config.daemonWideKnobs

internal class GatewayMoveChecks(private val topology: Topology) {
    private val keys = TomlKeys()

    fun findings(): List<TopologyFinding> = heads() + providers()

    private fun heads(): List<TopologyFinding> = topology.heads.flatMap { (key, head) ->
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

    /** The head's selector prefix, which the gateway reads from `[door.selector_aliases]` once the head is gone. */
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
        return listOf(
            TopologyFinding(
                "heads.$key.discovery_prefix",
                "the gateway reads this prefix only from [door.selector_aliases]; the head keeps it for its command",
                move = move,
            ),
        )
    }

    /** The head's window, tiers and standing prompt, each against the provider's own. */
    private fun serving(key: String, head: HeadConfig, provider: ProviderConfig): List<TopologyFinding> {
        val table = "providers.${keys.render(head.provider)}"
        return listOfNotNull(
            held(key, "context_window", head.contextWindow, provider.contextWindow, table),
            held(key, "model_slots", head.modelSlots.ifEmpty { null }, provider.modelSlots.ifEmpty { null }, table),
            held(key, "system_prompt", head.systemPrompt, provider.systemPrompt, table),
            held(key, "system_prompt_file", head.systemPromptFile, provider.systemPromptFile, table),
            held(key, "system_prompt_mode", head.systemPromptMode, provider.systemPromptMode, table),
            embeddedTiers(key, head, provider),
        )
    }

    /** The tiers a head names on its own model rows (`slot`), which the gateway reads from the provider's `model_slots`
     *  instead. They move as that map and the head keeps its allowlist and its slots as they are. */
    private fun embeddedTiers(key: String, head: HeadConfig, provider: ProviderConfig): TopologyFinding? {
        val table = "providers.${keys.render(head.provider)}"
        val said = head.tierModels()
        if (head.models.orEmpty().none { it.slot != null } || said == provider.tierModels) return null
        val free = provider.modelSlots.isEmpty()
        val why = if (free) "only this head says it" else "the provider says another"
        val map = said.entries.joinToString(", ", "{ ", " }") { (slot, id) -> "${keys.render(slot)} = ${quoted(id)}" }
        val move = GatewayMove(table, "model_slots", map, keepsSource = true).takeIf { free }
        return TopologyFinding("heads.$key.models", unserved(why), move = move)
    }

    /** A finding when the head says [field] and its provider does not say the same. It moves to the provider's table
     *  when the provider says nothing; when the provider says something else, the person chooses which stands. */
    private fun <T : Any> held(head: String, field: String, said: T?, provider: T?, table: String): TopologyFinding? {
        if (said == null || said == provider) return null
        val free = provider == null
        val why = if (free) "only this head says it" else "the provider says another"
        val move = GatewayMove(table, field, value = null, keepsSource = false).takeIf { free }
        return TopologyFinding("heads.$head.$field", unserved(why), move = move)
    }

    /** [text] as a TOML string, which a value is and a key need not be. */
    private fun quoted(text: String): String = "\"${keys.escape(text)}\""

    /** Whether [knob] is a finding of this check: not a capture or console knob (those
     *  stay with a head), and not a daemon-wide one, which a head already cannot set and whose finding offers the move
     *  for the serving bounds a provider may carry (TopologyChecks.knobs). */
    private fun servedByProvider(knob: Knob): Boolean = !knob.headOnly && knob !in daemonWideKnobs

    private fun unserved(why: String): String = "$why, so the gateway would serve the provider without it"

    /** Each override the gateway reads that the head sets and its provider does not say the same. */
    private fun overrides(key: String, head: HeadConfig, provider: ProviderConfig): List<TopologyFinding> =
        head.overrides.mapNotNull { (knob, value) ->
            val known = Knob.entries.firstOrNull { it.key == knob }?.takeIf(::servedByProvider)
            if (known == null || provider.overrides[knob] == value) return@mapNotNull null
            val free = knob !in provider.overrides
            val why = if (free) "only this head says it" else "the provider says another"
            val table = "providers.${keys.render(head.provider)}.overrides"
            val move = GatewayMove(table, knob, value = null, keepsSource = false).takeIf { free }
            TopologyFinding("heads.$key.overrides.$knob", unserved(why), move = move)
        }
}
