// NEW: v0.4.0 FEATURES.md §1 — the profiles `splice add` knows — one per auth-kind/dialect pair
// Topology already validates — as DATA: the wire shape, the default head and wrapper command, the
// starter models. The command asks the operator only for what a profile cannot know (a base URL
// for a generic OpenAI-compatible endpoint, models where the profile ships none), and this class
// renders the two TOML tables that are APPENDED to the operator's file, never merged into it.
package splice.configuration.add

import splice.core.model.ModelRates

// V4-35: the content blocks DeepSeek's Anthropic-compatibility table marks Supported.
// Everything absent here — redacted_thinking, image, document, search_result, mcp_tool_use,
// mcp_tool_result, container_upload, code_execution_tool_result — they reject.
// ORACLE 2026-09-16: NOT a reading of DeepSeek's compatibility table — the table says what is
// SUPPORTED, which is a different claim from what is REJECTED, and reading the first as the second
// is how image and document spent a campaign being dropped. This is the endpoint's own accepted set,
// read out of its deserializer by POSTing an unknown variant: it answers `unknown variant X,
// expected one of ...` and names all nine. Re-probe rather than edit from docs.

/** One model row; [slots] are the Claude model slots a passthrough head maps it to (fable/opus/...). */
internal data class AddModel(
    val id: String,
    val label: String,
    val contextWindow: Long,
    val slots: List<String> = emptyList(),
    /** V4-37: this row's rate card, USD per million tokens, so a freshly added head prices its own
     *  spend instead of the client's Anthropic-priced figure. Null = no card, and the statusline
     *  renders the client's number exactly as it did before. */
    val rates: ModelRates? = null,
    /** V4-232: the Claude model the client resolves this row as (ModelEntry.clientModel). Null = none. */
    val clientModel: String? = null,
) {
    /** The rate card emitted INLINE on the model row — never as a `[providers.X.models.rates]`
     *  sub-table. That spelling repeats its header once per model, and splice's own
     *  TomlStructurePreflight.rejectDuplicateModelKeys refuses a repeated single-bracket header
     *  ("defined twice ... ktoml silently merges both bodies"), so it is a hard parse error for any
     *  provider with more than one model. Inline is the only form that survives. */
    internal fun ratesLine(): List<String> = rates?.let { r ->
        val write = r.cacheWrite?.let { w -> ", cache_write = $w" }.orEmpty()
        // V4-240: the long-context tier rides FLAT on the same inline card, `long_context_` keys, for
        // the reason above and because ktoml cannot read a table nested in it (ModelRatesToml).
        val tier = r.longContext?.let { t ->
            val tierWrite = t.cacheWrite?.let { w -> ", long_context_cache_write = $w" }.orEmpty()
            ", long_context_over_input_tokens = ${t.overInputTokens}, long_context_input = ${t.input}, " +
                "long_context_cache_read = ${t.cacheRead}, long_context_output = ${t.output}$tierWrite"
        }.orEmpty()
        listOf("rates = { input = ${r.input}, cache_read = ${r.cacheRead}, output = ${r.output}$write$tier }")
    } ?: emptyList()
}

/** One profile. What `splice setup` reads to tick a head — [name], [AddHeadSpec.key], [AddProviderSpec.authKind] —
 *  is public (LAYOUT-01); the rest is this verb's, and only this module constructs one. */
@ConsistentCopyVisibility
public data class AddProfile internal constructor(
    public val name: String,
    /** Null when the operator must supply it (`--base-url`). */
    internal val baseUrl: String?,
    public val provider: AddProviderSpec,
    public val head: AddHeadSpec,
    internal val labels: AddLabels,
    internal val models: List<AddModel>,
    internal val policy: AddModelPolicy = AddModelPolicy(),
) {
    /** What added the row, named in its TOML comment and the verb's title. */
    internal val origin: String get() = labels.origin ?: "splice add $name"
}

public class AddProfiles {

    private val profiles = AddProfileCatalog().rows

    public fun find(name: String): AddProfile? = profiles.firstOrNull { it.name == name }

    internal fun describe(): List<String> = profiles.map { "${it.name.padEnd(NAME_PAD)} ${it.labels.summary}" }

    /** Every profile `splice add` knows. The wizard ticks from this list, never a typed roster. */
    public fun catalog(): List<AddProfile> = profiles

    /** The env var an api-key provider reads: the key upper-cased with dashes as underscores. */
    public fun apiKeyEnv(key: String): String = key.uppercase().replace('-', '_') + "_API_KEY"

    /** One provider table and one head table for [key], appended verbatim; every value quoted.
     *  [profile] is the RESOLVED one: base URL, command and models already filled in by the command. */
    internal fun toml(profile: AddProfile, key: String, port: Int): String {
        val models = profile.models
        val auth = if (profile.provider.authKind == API_KEY) {
            """auth = { kind = "api-key", env = "${apiKeyEnv(key)}" }"""
        } else {
            """auth = { kind = "${profile.provider.authKind}" }"""
        }
        val provider = listOf(
            "",
            "# Added by `${profile.origin}`.",
            "[providers.$key]",
            "dialect = \"${profile.provider.dialect}\"",
            "base_url = \"${profile.baseUrl.orEmpty()}\"",
            auth,
        ) + profile.provider.extra + models.flatMap { m ->
            listOf(
                "[[providers.$key.models]]",
                "id = \"${m.id}\"",
                "label = \"${m.label}\"",
                "context_window = ${m.contextWindow}",
            ) + m.clientModel?.let { listOf("client_model = \"$it\"") }.orEmpty() + m.ratesLine()
        }
        val mappings = models.flatMap { m -> m.slots.map { slot -> m.id to slot } }
        val pinned = models.first()
        val head = listOf(
            "",
            "[heads.$key]",
            "provider = \"$key\"",
            "port = $port",
            "discovery_prefix = \"claude-$key--\"",
            "pinned_model = \"${pinned.id}\"",
        ) + headExtras(mappings, pinned.contextWindow, profile.policy.discoverRoster) + listOf(
            "[heads.$key.claude]",
            "command = \"${profile.head.command}\"",
        )
        return (provider + head).joinToString("\n") + "\n"
    }

    /** Discovery profiles keep row windows and map tiers separately. Other profiles keep their
     *  pinned window and their existing serving allowlist when they declare tiers. */
    private fun headExtras(
        mappings: List<Pair<String, String>>,
        pinnedWindow: Long,
        discoverRoster: Boolean,
    ): List<String> {
        if (discoverRoster) {
            return listOf("model_slots = {" + mappings.joinToString { (id, slot) -> "$slot = \"$id\"" } + "}")
        }
        val window = listOf("context_window = $pinnedWindow")
        return if (mappings.isEmpty()) {
            window
        } else {
            listOf(
                "models = [" +
                    mappings.joinToString { (id, slot) -> "{ id = \"$id\", slot = \"$slot\" }" } + "]",
            ) + window
        }
    }
}

private const val NAME_PAD = 12
