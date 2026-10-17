package splice.client.models

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.client.Keys
import splice.client.MaterializeSpec

/** Pure model-selection and picker policy; the materializer owns filesystem writes and hooks. */
internal object ClaudeModelSettings {
    private const val MODEL_OPTIONS_CACHE = "additionalModelOptionsCache"

    fun base(global: JsonObject, saved: String?, spec: MaterializeSpec): JsonObject {
        val allow = spec.availableModelIds
        return buildJsonObject {
            global.forEach { (key, value) -> if (carried(key, clientPicks = allow == null)) put(key, value) }
            if (allow != null) {
                putJsonArray(Keys.AVAILABLE_MODELS) { allow.forEach { add(it) } }
                put("enforceAvailableModels", true)
            }
            chosen(saved, spec)?.let { put(Keys.MODEL, it) }
        }
    }

    /** Head overrides win their keys; a client-owned mapping of any other model remains intact. */
    fun overrides(global: JsonObject, own: Map<String, String>): JsonObject? {
        if (own.isEmpty()) return null
        val shared = (global[Keys.MODEL_OVERRIDES] as? JsonObject).orEmpty()
        return JsonObject(shared + own.mapValues { (_, id) -> JsonPrimitive(id) })
    }

    fun withLaunchCache(local: JsonObject, cache: JsonElement?): JsonObject =
        JsonObject(if (cache == null) local - MODEL_OPTIONS_CACHE else local + (MODEL_OPTIONS_CACHE to cache))

    /** With no head roster, preserve the client's saved choice without inventing one. */
    private fun chosen(saved: String?, spec: MaterializeSpec): String? {
        val allow = spec.availableModelIds ?: return saved
        return if (saved != null && saved in allow) saved else spec.defaultModel
    }

    private fun carried(key: String, clientPicks: Boolean): Boolean =
        key != Keys.STATUS_LINE && key != Keys.HOOKS &&
            (clientPicks || key != Keys.MODEL && key != Keys.AVAILABLE_MODELS)
}
