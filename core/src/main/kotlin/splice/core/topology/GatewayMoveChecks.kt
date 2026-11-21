// NEW: gateway-no-heads. A value only a head block holds, and that would make the gateway serve that head's provider
// differently, is a boot finding that names it: the gateway serves a provider with no head, from the provider's own
// table, so what a head alone says (an older selector prefix, a window, the tiers' models, the standing prompt, an
// override) would be lost the day the head goes. Each finding says where the value moves to (GatewayMove), and the fix
// session offers the move. A head whose values the provider already carries the same way is no finding; a pin is where
// the head's own command starts, and capture and console knobs stay with the head. The values a provider carries for
// the gateway are checked here too, at the provider's own path.
package splice.core.topology

import splice.core.config.Knob

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
        val own = "claude-${head.provider}--"
        val said = head.discoveryPrefix
        if (said == own || topology.door.selectorAliases[said] == head.provider) return emptyList()
        val move = GatewayMove(
            table = "door.selector_aliases",
            key = keys.render(said),
            value = keys.render(head.provider),
            keepsSource = true,
        )
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
        )
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

    private fun unserved(why: String): String = "$why, so the gateway would serve the provider without it"

    /** Each override the gateway reads that the head sets and its provider does not say the same. */
    private fun overrides(key: String, head: HeadConfig, provider: ProviderConfig): List<TopologyFinding> =
        head.overrides.mapNotNull { (knob, value) ->
            val known = Knob.entries.firstOrNull { it.key == knob }?.takeUnless { it.headOnly }
            if (known == null || provider.overrides[knob] == value) return@mapNotNull null
            val free = knob !in provider.overrides
            val why = if (free) "only this head says it" else "the provider says another"
            val table = "providers.${keys.render(head.provider)}.overrides"
            val move = GatewayMove(table, knob, value = null, keepsSource = false).takeIf { free }
            TopologyFinding("heads.$key.overrides.$knob", unserved(why), move = move)
        }
}
