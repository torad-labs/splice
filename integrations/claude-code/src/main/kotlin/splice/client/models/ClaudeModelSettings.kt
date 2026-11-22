package splice.client.models

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.ClaudeModelLists
import splice.client.Keys
import splice.client.desktop.DesktopSettings
import splice.client.desktop.GATEWAY_BASE_URL
import splice.client.desktop.GATEWAY_DISCOVERY
import splice.client.desktop.GATEWAY_TOKEN
import splice.client.desktop.GatewayMode

/** What a head's own settings.json carries of the person's settings, and what its .claude.json keeps. head-catalog
 *  (Marcos, Nov 19, 2026: one list, the Splice gateway's): no model list and no model choice, of splice's or carried
 *  from the person's file. Not `availableModels`, `enforceAvailableModels`, `modelPicker`, `modelOverrides` or `model`,
 *  no variable in `env` that lays a list or sends a session past the gateway, and no `additionalModelOptionsCache`. An
 *  older launch's are taken out at the next one, since settings.json is written whole and the cache key is dropped. The
 *  person's other keys in a head's own file keep working, as they did. */
internal object ClaudeModelSettings {
    private val desktop = DesktopSettings()
    private val lists = ClaudeModelLists.settingsKeys.toSet() + Keys.MODEL
    private val pastGateway = GatewayMode().unset.toSet() + GATEWAY_BASE_URL + GATEWAY_TOKEN + GATEWAY_DISCOVERY + MODEL

    /** What a head takes from the person's [global] settings: desktop-door's inherited keys, less anything that would
     *  lay a model list of its own. */
    fun inherited(global: JsonObject): JsonObject =
        JsonObject(desktop.inherited(global).filterKeys(::carried))

    /** [fromGlobal] with the head's [own] keys over it, which no more lay a model list than global's do. */
    fun base(fromGlobal: JsonObject, own: JsonObject): JsonObject = buildJsonObject {
        (fromGlobal + desktop.inherited(own)).forEach { (key, value) ->
            when {
                key == ENV && value is JsonObject -> put(key, JsonObject(value - pastGateway))
                carried(key) -> put(key, value)
            }
        }
    }

    /** [local], a head's .claude.json, without the picker cache an older launch laid there. */
    fun withoutLaunchCache(local: JsonObject): JsonObject = JsonObject(local - ClaudeModelLists.STATE_CACHE)

    private fun carried(key: String): Boolean = key != Keys.STATUS_LINE && key != Keys.HOOKS && key !in lists
}

private const val ENV = "env"
private const val MODEL = "ANTHROPIC_MODEL"
