// NEW: v0.4.0 FEATURES.md §1 — the profiles `splice add` knows — one per auth-kind/dialect pair
// Topology already validates — as DATA: the wire shape, the default head and wrapper command, the
// starter models. The command asks the operator only for what a profile cannot know (a base URL
// for a generic OpenAI-compatible endpoint, models where the profile ships none), and this class
// renders the two TOML tables that are APPENDED to the operator's file, never merged into it.
package splice.app.cli

private const val WINDOW_200K = 200_000L
private const val WINDOW_262K = 262_144L
private const val WINDOW_272K = 272_000L
private const val WINDOW_400K = 400_000L
private const val WINDOW_500K = 500_000L
private const val WINDOW_1M = 1_000_000L

/** One model row; [slots] are the Claude model slots a passthrough head maps it to (fable/opus/...). */
internal data class AddModel(
    val id: String,
    val label: String,
    val contextWindow: Long,
    val slots: List<String> = emptyList(),
)

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

    private val profiles = listOf(
        AddProfile(
            name = "codex",
            summary = "ChatGPT subscription over the Responses API (browser sign-in)",
            dialect = "openai-responses",
            authKind = "chatgpt-oauth",
            baseUrl = "https://chatgpt.com/backend-api/codex",
            headKey = "codex",
            command = "claudex",
            models = listOf(
                AddModel("gpt-5.6-sol", "GPT-5.6 Sol", WINDOW_400K),
                AddModel("gpt-5.5", "GPT-5.5", WINDOW_272K),
                AddModel("gpt-5.4-mini", "GPT-5.4 mini", WINDOW_272K),
            ),
        ),
        AddProfile(
            name = "grok",
            summary = "xAI SuperGrok subscription over the Responses API (browser sign-in)",
            dialect = "openai-responses",
            authKind = "grok-oauth",
            baseUrl = "https://api.x.ai/v1",
            headKey = "grok",
            command = "claude-grok",
            models = listOf(
                AddModel("grok-4.6", "Grok 4.6", WINDOW_500K),
                AddModel("grok-4.5", "Grok 4.5", WINDOW_500K),
            ),
        ),
        AddProfile(
            name = "kimi",
            summary = "Moonshot Kimi subscription over the Anthropic wire (device sign-in)",
            dialect = "anthropic-passthrough",
            authKind = "kimi-oauth",
            baseUrl = "https://api.kimi.com/coding",
            headKey = "kimi",
            command = "claude-kimi",
            models = listOf(
                AddModel("k3-256k", "Kimi K3 256k", WINDOW_262K),
                AddModel("k3[1m]", "Kimi K3 (1M)", WINDOW_1M),
                AddModel("kimi-for-coding", "Kimi for Coding", WINDOW_262K),
            ),
        ),
        AddProfile(
            name = "muse",
            summary = "Meta Muse subscription over the Anthropic wire (device sign-in)",
            dialect = "anthropic-passthrough",
            authKind = "muse-oauth",
            baseUrl = "https://api.meta.ai",
            headKey = "muse",
            command = "claude-muse",
            models = listOf(
                AddModel("muse-spark-1.3[1m]", "Muse Spark 1.3", WINDOW_1M),
                AddModel("muse-spark-1.2[1m]", "Muse Spark 1.2", WINDOW_1M),
            ),
        ),
        AddProfile(
            name = "claude",
            summary = "Anthropic with your own Claude login, forwarded untouched",
            dialect = "anthropic-passthrough",
            authKind = "client",
            baseUrl = "https://api.anthropic.com",
            headKey = "claude-splice",
            command = "claude-splice",
            models = listOf(
                AddModel("claude-fable-5", "Claude Fable 5", WINDOW_200K, listOf("fable")),
                AddModel("claude-opus-5", "Claude Opus 5", WINDOW_200K, listOf("opus")),
                AddModel("claude-sonnet-5", "Claude Sonnet 5", WINDOW_200K, listOf("sonnet")),
                AddModel("claude-haiku-4-5", "Claude Haiku 4.5", WINDOW_200K, listOf("haiku")),
            ),
            providerExtra = listOf("""extra_headers = { anthropic-version = "2023-06-01" }"""),
        ),
        AddProfile(
            name = "api-key",
            summary = "any OpenAI-compatible chat endpoint with an API key (OpenRouter, Fireworks, a local runtime)",
            dialect = "openai-chat",
            authKind = "api-key",
            baseUrl = null,
            headKey = "",
            command = "",
            models = emptyList(),
        ),
    )

    fun find(name: String): AddProfile? = profiles.firstOrNull { it.name == name }

    fun describe(): List<String> = profiles.map { "${it.name.padEnd(NAME_PAD)} ${it.summary}" }

    /** The env var an api-key provider reads: the key upper-cased with dashes as underscores. */
    fun apiKeyEnv(key: String): String = key.uppercase().replace('-', '_') + "_API_KEY"

    /** One provider table and one head table for [key], appended verbatim; every value quoted.
     *  [profile] is the RESOLVED one: base URL, command and models already filled in by the command. */
    fun toml(profile: AddProfile, key: String, port: Int): String {
        val models = profile.models
        val auth = if (profile.authKind == "api-key") {
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
            )
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

private const val NAME_PAD = 8
