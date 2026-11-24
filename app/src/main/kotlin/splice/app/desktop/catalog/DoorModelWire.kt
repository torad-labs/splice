package splice.app.desktop.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.models.AnthropicTiers

/** One model-list format for every door path, carrying the provider's published properties. */
internal class DoorModelWire(private val tiers: AnthropicTiers, private val newest: Map<String, String>) {
    fun discovered(model: ServedModel, id: String): JsonObject = buildJsonObject {
        model.fact.source.orEmpty().forEach { (key, value) -> put(key, value) }
        identity(this, model, id)
        windows(this, model)
        reasoning(this, model)
    }

    private fun identity(into: JsonObjectBuilder, model: ServedModel, id: String) {
        into.put("type", "model")
        into.put("id", id)
        into.put("display_name", model.fact.label)
        if (model.fact.description.isNotBlank()) into.put("description", model.fact.description)
        model.claude?.let(tiers::tierOf)?.let { tier ->
            into.put("anthropic_family_tier", tier)
            if (newest[tier] == model.claude) into.put("is_family_default", true)
        }
    }

    /** The model's windows, as Desktop reads them: whether it has a 1M variant, read against splice.toml's
     *  `[desktop]` big window (ServedModel.millionWindow), and its provider's real ceiling, never spelled past
     *  it — where the configured Code-tab window differs from the model's own, scaling the counts a turn
     *  reports carries the difference, not this number. */
    private fun windows(into: JsonObjectBuilder, model: ServedModel) {
        if (model.supports1m || model.fact.source?.containsKey("supports_1m") != true) {
            into.put("supports_1m", model.supports1m)
        }
        if (model.prefer1m) into.put("prefer_1m", true)
        if (model.fact.contextWindow > 0) into.put(INPUT_TOKENS, model.fact.contextWindow)
    }

    private fun reasoning(into: JsonObjectBuilder, model: ServedModel) {
        DesktopEffort.max(model)?.let { into.put("max_effort_level", it) }
        selector(into, model.fact)
    }

    private fun selector(into: JsonObjectBuilder, fact: CatalogModel) {
        if (fact.reasons && fact.source?.containsKey("supports_reasoning") != true) into.put("supports_reasoning", true)
        capabilities(fact)?.let { into.put("capabilities", it) }
        if (fact.reasoningLevels.isNotEmpty() && fact.source?.containsKey(THINKING_KEY) != true) {
            into.put(THINKING_KEY, thinking(fact))
        }
        if (fact.source?.get(RUNTIME) == null || fact.source[RUNTIME] is JsonObject) into.put(RUNTIME, runtime(fact))
    }

    private fun thinking(fact: CatalogModel): JsonObject = buildJsonObject {
        put("type", EFFORT)
        put(
            "effort_options",
            buildJsonArray {
                fact.reasoningLevels.forEach { level ->
                    addJsonObject {
                        put("id", level)
                        put("name", EFFORT_NAMES[level] ?: level.replaceFirstChar { it.titlecase() })
                        if (level == fact.defaultReasoningLevel) put("recommended", true)
                    }
                }
            },
        )
    }

    private fun runtime(fact: CatalogModel): JsonObject = buildJsonObject {
        val source = fact.source?.get(RUNTIME) as? JsonObject
        source.orEmpty().forEach { (key, value) -> put(key, value) }
        if (fact.contextWindow > 0 && source?.containsKey(INPUT_TOKENS) != true) put(INPUT_TOKENS, fact.contextWindow)
        if (fact.reasoningLevels.isNotEmpty()) {
            if (source?.containsKey("effort_levels") != true) {
                put("effort_levels", JsonArray(fact.reasoningLevels.map(::JsonPrimitive)))
            }
            if (source?.containsKey("default_effort") != true) {
                fact.defaultReasoningLevel?.let { put("default_effort", it) }
            }
        }
    }

    private fun capabilities(fact: CatalogModel): JsonObject? {
        val source = fact.source?.get("capabilities")
        if (source != null && source !is JsonObject) return null
        return supportedCapabilities(fact, source)
    }

    private fun supportedCapabilities(fact: CatalogModel, source: JsonObject?): JsonObject? {
        if (fact.reasoningLevels.isEmpty()) return source
        val existing = source?.get(EFFORT)
        if (existing != null && existing !is JsonObject) return source
        return buildJsonObject {
            source.orEmpty().forEach { (key, value) -> put(key, value) }
            put(EFFORT, effort(fact, existing))
        }
    }

    private fun effort(fact: CatalogModel, existing: JsonObject?): JsonObject = buildJsonObject {
        existing.orEmpty().forEach { (key, value) -> put(key, value) }
        if (existing?.containsKey(SUPPORTED) != true) put(SUPPORTED, true)
        fact.reasoningLevels.forEach { level ->
            if (existing?.containsKey(level) != true) put(level, buildJsonObject { put(SUPPORTED, true) })
        }
    }
}

internal object DesktopEffort {
    val levels = listOf("low", "medium", "high", "xhigh", "max")
    fun max(model: ServedModel): String? = levels.lastOrNull { it in model.fact.reasoningLevels }
}

private const val THINKING_KEY = "thinking"
private const val RUNTIME = "runtime"
private const val EFFORT = "effort"
private const val SUPPORTED = "supported"
private const val INPUT_TOKENS = "max_input_tokens"
private val EFFORT_NAMES =
    mapOf("low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra", "max" to "Max")
