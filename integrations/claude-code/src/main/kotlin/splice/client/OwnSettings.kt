// NEW: tell the keys a head's own settings.json sets itself from the ones it took from the person's global file.
package splice.client

import kotlinx.serialization.json.JsonObject

/** The keys a head's settings.json holds of its own, which a launch keeps over the person's global value. A key
 *  splice takes from global is recorded beside the hook origins ([HookSettings.ORIGINS]), so the next launch knows
 *  it as global's: it follows global, and goes when global drops it. A key holding anything else is the head's. */
internal object OwnSettings {
    /** The origins record's entry for the keys taken from global, each with the value it was taken at. */
    const val RECORD = "settings_inherited"

    /** The keys splice writes on every launch, never the head's own. */
    private val splices = setOf(
        Keys.MODEL,
        Keys.AVAILABLE_MODELS,
        "enforceAvailableModels",
        Keys.STATUS_LINE,
        Keys.HOOKS,
        Keys.API_KEY_HELPER,
        Keys.OTEL_HEADERS_HELPER,
        Keys.MODEL_OVERRIDES,
    )

    /** What [head], the head's settings.json as the last launch left it, sets of its own: every key splice doesn't
     *  write whose value is not the one global gave it at the last launch, as [origins] records it. With no record
     *  yet (a head first launched before the record), a key equal to what this launch takes from global
     *  ([fromGlobal]) is global's. */
    fun own(head: JsonObject, fromGlobal: JsonObject, origins: JsonObject): JsonObject {
        val taken = origins[RECORD] as? JsonObject ?: fromGlobal
        return JsonObject(head.filter { (key, value) -> key !in splices && taken[key] != value })
    }

    /** [origins] with what this launch took from global: [fromGlobal] less the keys the head sets of its own. */
    fun recorded(origins: JsonObject, fromGlobal: JsonObject, own: JsonObject): JsonObject =
        JsonObject(origins + (RECORD to JsonObject(fromGlobal - own.keys)))
}
