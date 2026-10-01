// NEW: 2026-09-23 (concentration) — the head's half of the TOML topology schema, moved out of Topology.kt
// unchanged when 5a4361dc4's roster rules took Topology.kt over the band-HIGH line on the layout tree.
// HeadConfig.provider is still a key into Topology.providers, the invariant ProviderConfig.catalogFor
// reads; only the file changed.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.core.model.ModelRates
import splice.core.prompt.HeadSystemPrompt
import splice.core.prompt.SystemPromptMode
import splice.core.util.TopologySlotsFailure
import java.nio.file.Path

@Serializable
public data class HeadModel(
    val id: String,
    val slot: String? = null,
)

@Serializable
public data class HeadConfig(
    val provider: String,
    val port: Int,
    @SerialName("discovery_prefix") val discoveryPrefix: String,
    /** Required on every head except one whose provider forwards the client's own Claude login, where
     *  the client picks its models; a pin there selects only the launch default, never the roster. */
    @SerialName("pinned_model") val pinnedModel: String = "",
    val models: List<HeadModel>? = null,
    /** Tier mappings without a serving allowlist. [models] still restricts the roster; its entries
     *  must not carry slots alongside this map. Keys are tiers, values are model ids. */
    @SerialName("model_slots") val modelSlots: Map<String, String> = emptyMap(),
    @SerialName("context_window") val contextWindow: Long? = null,
    val overrides: Map<String, String> = emptyMap(),
    val claude: ClaudeWrapperConfig = ClaudeWrapperConfig(),
    /** V4-36: standing instructions this head places on EVERY turn. Inline text or
     *  [systemPromptFile] — never both (the resolver makes that a config error at load) — and
     *  [systemPromptMode] picks the seam: `append` (the default) adds the text beside Claude
     *  Code's own system field, `replace` SUBSTITUTES it, which strips the harness instructions
     *  Claude Code ships in that field. Absent, or empty, is exactly today's bytes. */
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("system_prompt_file") val systemPromptFile: String? = null,
    @SerialName("system_prompt_mode") val systemPromptMode: SystemPromptMode? = null,
    /** V4-37: this head's OWN rate card, keyed by model id, USD per million tokens — an account
     *  tier or a reseller markup that differs from the provider's published card. It WINS over the
     *  provider model entry for the ids it names; an id it does not name keeps the provider's rates,
     *  and with neither declared the statusline falls back to the client's own `total_cost_usd`
     *  exactly as it does today. The case this exists for is two heads on ONE provider billed
     *  differently — every other head is already correct from the provider entry alone.
     *
     *  It is folded into the catalog by [catalogFor] rather than threaded separately, because the
     *  catalog is what the statusline already receives: the head's card then reaches the cost
     *  segment without a new field on the runtime head handle or a second wiring path. */
    val rates: Map<String, ModelRates>? = null,
) {
    init {
        val slots = modelSlots.keys.map { it.trim('"', '\'').lowercase() }
        if (slots.any { it !in headModelSlots }) throw TopologySlotsFailure(TopologySlotsFailure.Problem.UNKNOWN)
        if (slots.distinct().size != slots.size) throw TopologySlotsFailure(TopologySlotsFailure.Problem.DUPLICATE)
        if (modelSlots.values.any(String::isBlank)) throw TopologySlotsFailure(TopologySlotsFailure.Problem.BLANK_ID)
        if (modelSlots.values.distinct().size != modelSlots.size) {
            throw TopologySlotsFailure(TopologySlotsFailure.Problem.DUPLICATE_ID)
        }
        if (modelSlots.isNotEmpty() && models.orEmpty().any { it.slot != null }) {
            throw TopologySlotsFailure(TopologySlotsFailure.Problem.COMPETING)
        }
    }

    /** The id-to-tier map the launcher consumes, independent of the provider's discovered roster. */
    public fun tierSlots(): Map<String, String> = if (modelSlots.isNotEmpty()) {
        modelSlots.entries.associate { (slot, id) -> id to slot.trim('"', '\'').lowercase() }
    } else {
        models.orEmpty().mapNotNull { model -> model.slot?.let { model.id to it.lowercase() } }.toMap()
    }

    /** V4-36: this head's standing prompt as its resolver. An ABSENT `system_prompt_mode` is the
     *  documented default rather than a missing value — an operator who names no mode gets
     *  `append` — so the default lives here, once, next to the schema that documents it, instead
     *  of being re-spelt at each wiring site. [key] names the head in the resolved source. */
    public fun systemPromptFor(key: String, configDir: Path): HeadSystemPrompt = HeadSystemPrompt(
        text = systemPrompt,
        file = systemPromptFile,
        mode = systemPromptMode ?: SystemPromptMode.APPEND,
        configDir = configDir,
        source = "head:$key",
    )
}
