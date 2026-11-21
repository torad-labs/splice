package splice.app.desktop.catalog

import java.net.URI
import java.net.URISyntaxException

/** Display facts providers omit from their model-list response. A provider's or operator's facts take precedence.
 *  Host and exact model id are both required, so a different provider is never given another vendor's metadata.
 *  Sources: https://dev.meta.ai/docs/models, https://dev.meta.ai/docs/choose-a-model,
 *  https://www.kimi.com/code/docs/en/kimi-code/models.html, https://docs.x.ai/developers/models,
 *  https://platform.claude.com/docs/en/models/overview, https://api-docs.deepseek.com/ and Claude's published model
 *  catalog. */
internal object KnownModelMetadata {
    fun find(baseUrl: String, id: String): KnownModel? {
        val host = try {
            URI(baseUrl).host?.lowercase()
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        return models[host]?.get(id)
    }

    private val models = mapOf(
        "api.meta.ai" to mapOf(
            "muse-wonder-full-1.0" to KnownModel(
                "Muse Wonder Full 1.0",
                "High-capability model for complex coding and agent work.",
                MUSE_ALL,
                "max",
            ),
            "muse-wonder-full-1.0-contributor" to KnownModel(
                "Muse Wonder Full 1.0 Contributor",
                CONTRIBUTOR_DESCRIPTION,
                MUSE_ALL,
                "max",
            ),
            "muse-wonder-1.0" to KnownModel(
                "Muse Wonder 1.0",
                "For complex software engineering and long-running agents.",
                MUSE_ALL,
                "max",
            ),
            "muse-wonder-1.0-contributor" to KnownModel(
                "Muse Wonder 1.0 Contributor",
                CONTRIBUTOR_DESCRIPTION,
                MUSE_ALL,
                "max",
            ),
            "muse-spark-1.3" to KnownModel("Muse Spark 1.3", "Agentic and coding work at scale.", MUSE_ALL, "high"),
            "muse-spark-1.2" to KnownModel("Muse Spark 1.2", "Agentic and coding work at scale.", MUSE_SPARK, "high"),
            "muse-spark-1.1" to KnownModel(
                "Muse Spark 1.1",
                "Original Muse Spark model for coding and agentic work.",
                MUSE_SPARK,
                "high",
            ),
            "muse-spark-1.3-contributor" to KnownModel(
                "Muse Spark 1.3 Contributor",
                CONTRIBUTOR_DESCRIPTION,
                MUSE_SPARK,
                "high",
            ),
            "muse-spark-1.2-contributor" to KnownModel(
                "Muse Spark 1.2 Contributor",
                CONTRIBUTOR_DESCRIPTION,
                MUSE_SPARK,
                "high",
            ),
            "muse-ember-1.0" to KnownModel(
                "Muse Ember 1.0",
                "Efficient model for high-volume, repeated tasks.",
                MUSE_ALL,
                "max",
            ),
            "assetgen-nano-1.0" to KnownModel("AssetGen Nano 1.0", "Meta lists this model without capability details."),
            "assetgen-pro-1.0" to KnownModel("AssetGen Pro 1.0", "Meta lists this model without capability details."),
        ),
        "api.kimi.com" to mapOf(
            "k3.1-pro" to KnownModel(description = "Kimi's flagship for coding and long-running agent work."),
            "k3.1-pro-highspeed" to KnownModel(
                description = "K3.1 Pro with faster output and about twice the quota usage.",
            ),
            "k3" to KnownModel(description = "Kimi K3 for large codebases and long-document tasks."),
            "k3-256k" to KnownModel(description = "Kimi K3 with a 256K context window."),
            "kimi-for-coding" to KnownModel(description = "K2.8 Preview for code completion and routine development."),
            "kimi-for-coding-highspeed" to KnownModel(description = "High-speed K2.7 Code for coding tasks."),
        ),
        "api.x.ai" to mapOf(
            "grok-4.7" to KnownModel(description = "Grok for coding, agentic tasks and knowledge work."),
            "grok-4.6" to KnownModel(description = "Grok 4.6 for coding and reasoning tasks."),
            "grok-build-latest" to KnownModel(description = "Grok Build for software engineering and agent workflows."),
            "grok-4.3" to KnownModel(description = "Grok 4.3 for tool use and instruction following."),
            "grok-4.20-0309-non-reasoning" to KnownModel("Grok 4.20 Non-Reasoning", "Grok 4.20 without reasoning."),
            "grok-4.20-0309-reasoning" to KnownModel("Grok 4.20 Reasoning", "Grok 4.20 with reasoning."),
            "grok-4.20-multi-agent-0309" to KnownModel("Grok 4.20 Multi-Agent", "Grok's multi-agent research model."),
        ),
        "api.anthropic.com" to mapOf(
            "claude-opus-5-5" to KnownModel(description = "For long-running agentic coding and knowledge work."),
            "claude-opus-5" to KnownModel(description = "Claude Opus for deep reasoning and long-running tasks."),
            "claude-sonnet-5-5" to KnownModel(
                description = "Balances speed and capability for coding and knowledge work.",
            ),
            "claude-sonnet-5" to KnownModel(description = "Claude Sonnet for coding and agentic tasks."),
            "claude-fable-5" to KnownModel(description = "Claude Fable for demanding reasoning and agentic work."),
            "claude-fable-5-1" to KnownModel(description = "Claude Fable for demanding reasoning and agentic work."),
            "claude-haiku-4-5" to KnownModel(description = "Claude Haiku for fast, efficient tasks."),
            "claude-fable-5-2" to KnownModel(description = "For demanding reasoning and long-horizon agentic work."),
            "claude-haiku-5-5" to KnownModel(
                description = "For fast, high-volume tasks such as classification and routing.",
            ),
            "claude-opus-4-8" to KnownModel(description = "Claude Opus for coding, reasoning and agentic work."),
            "claude-opus-4-7" to KnownModel(description = "Claude Opus for coding, reasoning and agentic work."),
            "claude-opus-4-6" to KnownModel(description = "Claude Opus for complex reasoning and coding."),
            "claude-opus-4-5-20251101" to KnownModel(description = "Claude Opus 4.5 with manual thinking."),
            "claude-sonnet-4-6" to KnownModel(description = "Claude Sonnet for everyday coding and knowledge work."),
            "claude-sonnet-4-5-20250929" to KnownModel(description = "Claude Sonnet 4.5 with manual thinking."),
        ),
        "api.deepseek.com" to mapOf(
            "deepseek-flash" to KnownModel(description = "DeepSeek V4.1 Flash with visual input and thinking modes."),
            "deepseek-v4-pro" to KnownModel(description = "DeepSeek V4 Pro with thinking and non-thinking modes."),
        ),
    )
}

internal data class KnownModel(
    val label: String = "",
    val description: String,
    val reasoningLevels: List<String> = emptyList(),
    val defaultEffort: String? = null,
)

private const val CONTRIBUTOR_DESCRIPTION = "Contributor mode: content may be used to train Meta models."
private val MUSE_SPARK = listOf("minimal", "low", "medium", "high", "xhigh")
private val MUSE_ALL = MUSE_SPARK + "max"
