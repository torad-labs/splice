// NEW: v0.4.0 FEATURES.md §1 — the profiles `splice add` knows — one per auth-kind/dialect pair
// Topology already validates — as DATA: the wire shape, the default head and wrapper command, the
// starter models. The command asks the operator only for what a profile cannot know (a base URL
// for a generic OpenAI-compatible endpoint, models where the profile ships none), and this class
// renders the two TOML tables that are APPENDED to the operator's file, never merged into it.
package splice.app.cli.add

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
) {
    /** The rate card emitted INLINE on the model row — never as a `[providers.X.models.rates]`
     *  sub-table. That spelling repeats its header once per model, and splice's own
     *  TomlStructurePreflight.rejectDuplicateModelKeys refuses a repeated single-bracket header
     *  ("defined twice ... ktoml silently merges both bodies"), so it is a hard parse error for any
     *  provider with more than one model. Inline is the only form that survives. */
    internal fun ratesLine(): List<String> = rates?.let { r ->
        val write = r.cacheWrite?.let { w -> ", cache_write = $w" }.orEmpty()
        listOf("rates = { input = ${r.input}, cache_read = ${r.cacheRead}, output = ${r.output}$write }")
    } ?: emptyList()
}

internal data class AddProfile(
    val name: String,
    val summary: String,
    val dialect: String,
    val authKind: String,
    /** Null when the operator must supply it (`--base-url`). */
    val baseUrl: String?,
    /** Default provider AND head key; empty when the operator must name it (`--name`). */
    val headKey: String,
    /** Default wrapper command; empty means `claude-<key>`. */
    val command: String,
    val models: List<AddModel>,
    /** Extra provider lines, already valid TOML (a default vendor header, for one). */
    val providerExtra: List<String> = emptyList(),
)

internal class AddProfiles {

    private val profiles = AddProfileCatalog().rows

    fun find(name: String): AddProfile? = profiles.firstOrNull { it.name == name }

    fun describe(): List<String> = profiles.map { "${it.name.padEnd(NAME_PAD)} ${it.summary}" }

    /** Every profile `splice add` knows. The wizard ticks from this list, never a typed roster. */
    fun catalog(): List<AddProfile> = profiles

    /** The env var an api-key provider reads: the key upper-cased with dashes as underscores. */
    fun apiKeyEnv(key: String): String = key.uppercase().replace('-', '_') + "_API_KEY"

    /** One provider table and one head table for [key], appended verbatim; every value quoted.
     *  [profile] is the RESOLVED one: base URL, command and models already filled in by the command. */
    fun toml(profile: AddProfile, key: String, port: Int): String {
        val models = profile.models
        val auth = if (profile.authKind == API_KEY) {
            """auth = { kind = "api-key", env = "${apiKeyEnv(key)}" }"""
        } else {
            """auth = { kind = "${profile.authKind}" }"""
        }
        val provider = listOf(
            "",
            "# Added by `splice add ${profile.name}`.",
            "[providers.$key]",
            "dialect = \"${profile.dialect}\"",
            "base_url = \"${profile.baseUrl.orEmpty()}\"",
            auth,
        ) + profile.providerExtra + models.flatMap { m ->
            listOf(
                "[[providers.$key.models]]",
                "id = \"${m.id}\"",
                "label = \"${m.label}\"",
                "context_window = ${m.contextWindow}",
            ) + m.ratesLine()
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
        ) + headExtras(mappings, pinned.contextWindow) + listOf(
            "[heads.$key.claude]",
            "command = \"${profile.command}\"",
        )
        return (provider + head).joinToString("\n") + "\n"
    }

    /** Head-wide window is the pinned row's ceiling, never the catalog max. Slot mappings are
     *  omitted when the profile has none, so the head keeps the provider-wide surface. */
    private fun headExtras(mappings: List<Pair<String, String>>, pinnedWindow: Long): List<String> {
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
