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
import splice.client.desktop.DesktopSettings

/** Pure model-selection and picker policy; the materializer owns filesystem writes and hooks. */
internal object ClaudeModelSettings {
    private const val MODEL_OPTIONS_CACHE = "additionalModelOptionsCache"
    private val desktop = DesktopSettings()

    /** What a head takes from the person's [global] settings. desktop-door: a head with a folder of its own carries
     *  nothing of the door the person's file may hold. */
    fun inherited(global: JsonObject, spec: MaterializeSpec): JsonObject =
        JsonObject(desktop.inherited(global).filterKeys { carried(it, clientPicks = spec.availableModelIds == null) })

    /** [fromGlobal] with the head's [own] keys over it, which no more send its sessions past it than global's do. */
    fun base(fromGlobal: JsonObject, own: JsonObject, saved: String?, spec: MaterializeSpec): JsonObject {
        val allow = spec.availableModelIds
        return buildJsonObject {
            (fromGlobal + desktop.inherited(own)).forEach { (key, value) -> put(key, value) }
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
