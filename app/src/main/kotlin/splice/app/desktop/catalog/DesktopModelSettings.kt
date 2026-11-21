package splice.app.desktop.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.models.AnthropicTiers

/** The catalog projection Desktop needs for its effort control, under the same ids as gateway discovery. */
internal class DesktopModelSettings(private val tiers: AnthropicTiers, private val newest: Map<String, String>) {
    fun models(models: List<ServedModel>): JsonArray = JsonArray(models.map(::model))

    private fun model(model: ServedModel): JsonObject = buildJsonObject {
        put("name", model.desktopId)
        if (model.claude == null) put("labelOverride", model.fact.label)
        if (model.supports1m) put("supports1m", true)
        if (model.prefer1m) put("prefer1m", true)
        tier(this, model)
        effort(this, model)
    }

    private fun tier(into: JsonObjectBuilder, model: ServedModel) {
        model.claude?.let(tiers::tierOf)?.let { tier ->
            into.put("anthropicFamilyTier", tier)
            if (newest[tier] == model.claude) into.put("isFamilyDefault", true)
        }
    }

    private fun effort(into: JsonObjectBuilder, model: ServedModel) {
        val control = newest["opus"].takeIf { model.claude == null && model.fact.reasoningLevels.isNotEmpty() }
        control?.let {
            into.put("behavesAs", it)
            DesktopEffort.max(model)?.let { level -> into.put("maxEffort", level) }
        }
    }
}
