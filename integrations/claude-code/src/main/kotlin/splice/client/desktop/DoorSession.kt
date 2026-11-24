// NEW: door-default (docs/specs/door-default.md, decisions 2 and 5). What a terminal `claude` is launched with to run
// on the Splice gateway: Claude Code's gateway mode at the gateway's address (GatewayMode), the terminal's own key as a
// bearer, the window the gateway declares, the model the session starts on, its own /login and /logout off, auto
// mode's classifier off, and nothing that would send it anywhere else. The session runs over the person's own Claude
// folder, so no CLAUDE_CONFIG_DIR is set and nothing of the person's is changed there (splice's /login, DoorLogin, is
// the one file it lays). head-catalog (Nov 19, 2026): `/model` is the gateway's list, which Claude Code discovers at
// startup; no list of splice's rides the launch, since a `modelPicker` with `replaceBuiltInOptions` hides discovered
// models and any lineup in `--settings` is the whole lineup. The Mantle switch the door launched with until then went
// with it: discovery does not run while any provider switch is set. A door another machine shares still carries the
// rows it is told (RemoteDoor), which is no list of splice's own.
package splice.client.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.client.Keys

/** One row of a terminal session's picker: the selector it sends, the model's own label, the head's command, and the
 *  model's own description in splice.toml (empty when it has none), shown before `splice · <command>`. */
public data class DoorPickerRow(
    val model: String,
    val label: String,
    val command: String,
    val description: String = "",
) {
    /** What /model shows under the label: the model's own description, then where it runs. */
    public val described: String
        get() = listOf(description.trim(), "splice · $command").filter(String::isNotEmpty).joinToString(" · ")
}

/** A launch of `claude` on the gateway: [door] its address, [key] the terminal's key, [window] the window it declares,
 *  [model] the selector a session starts on (null with no head in the gateway), and [rows] a shared door's picker,
 *  empty on this machine's own launch, which lists the gateway's models through discovery instead. */
public data class DoorSession(
    val door: String,
    val key: String,
    val window: Long,
    val model: String?,
    val rows: List<DoorPickerRow> = emptyList(),
    /** When false (side-by-side launch without splice's `commands/login.md` in `~/.claude`), Claude Code's built-in
     *  `/login` and `/logout` stay enabled instead of returning "Unknown command". */
    val disableLogin: Boolean = true,
) {
    private val gateway = GatewayMode()

    /** What the launch sets: [told], and the terminal's key. */
    public fun env(): Map<String, String> = told() + (GATEWAY_TOKEN to key)

    /** What the launch takes out of the environment it inherits: every other way to a provider, every model list but
     *  the gateway's, a starting model when the gateway names none, and `DISABLE_LOGIN_COMMAND`/`DISABLE_LOGOUT_COMMAND`
     *  when [disableLogin] is false so an inherited value cannot break Claude Code's built-in `/login`. */
    public fun unset(): List<String> = buildList {
        addAll(gateway.unset)
        if (model == null) add(DESKTOP_MODEL)
        if (!disableLogin) {
            add(LOGIN_OFF)
            add(LOGOUT_OFF)
        }
    }

    /** What the launch's `--settings` overlay carries in its `env` (#791): a person's settings `env` is applied over
     *  the process environment, so what the launch sets and takes out must be said again where only the overlay
     *  outranks it, a variable taken out spelled as [GatewayMode.cleared] says. Built from [told] and never from
     *  [env]: the key is a secret, and the overlay is an argument every local user can read. */
    public fun settingsEnv(): Map<String, String> = buildMap {
        putAll(gateway.cleared)
        if (model == null) put(DESKTOP_MODEL, "")
        if (!disableLogin) {
            put(LOGIN_OFF, "0")
            put(LOGOUT_OFF, "0")
        }
        putAll(told())
    }

    /** What the launch sets that is no secret: gateway mode at [door] on [model], the window, auto mode's classifier
     *  off, and Claude Code's own /login and /logout off when [disableLogin] is true. */
    private fun told(): Map<String, String> = buildMap {
        putAll(gateway.settings(door, model))
        put(DESKTOP_WINDOW, window.toString())
        put(DESKTOP_AUTO_MODE, "0")
        if (disableLogin) {
            put(LOGIN_OFF, "1")
            put(LOGOUT_OFF, "1")
        }
    }

    /** The `--settings` overlay: [settingsEnv] as its `env`, a shared door's picker, when it has rows, splice's launch
     *  mark where [marked], and [hooks] by event (splice's /login, DoorLogin), which Claude Code runs beside the
     *  person's own. No list of splice's: on this machine's own launch [rows] is empty and nothing of the sort is
     *  put. */
    public fun overlay(
        marked: Boolean,
        markKey: String,
        hooks: Map<String, List<JsonObject>> = emptyMap(),
    ): String = buildJsonObject {
        put(Keys.ENV, JsonObject(settingsEnv().mapValues { (_, value) -> JsonPrimitive(value) }))
        DoorPicker(rows).json(replace = true)?.let { put(DESKTOP_PICKER, it) }
        if (marked) put(markKey, true)
        if (hooks.isNotEmpty()) put(Keys.HOOKS, JsonObject(hooks.mapValues { (_, entries) -> JsonArray(entries) }))
    }.toString()
}

/** A session's picker as its `--settings` overlay carries it, one row per model of a signed-in account. */
internal class DoorPicker(private val rows: List<DoorPickerRow>) {
    /** The `modelPicker` value; null with no rows, so Claude Code lists the gateway's models alone. */
    public fun json(replace: Boolean): JsonObject? {
        if (rows.isEmpty()) return null
        return buildJsonObject {
            put(REPLACE_BUILT_IN, replace)
            putJsonArray(PICKER_OPTIONS) {
                rows.forEach { row ->
                    addJsonObject {
                        put("model", row.model)
                        put("label", row.label)
                        put("description", row.described)
                    }
                }
            }
        }
    }

    /** [overlay], a `--settings` JSON object, with these rows added after Claude Code's own models; [overlay] as it is
     *  with no rows. */
    public fun addedTo(overlay: String): String {
        val picker = json(replace = false) ?: return overlay
        val settings = Json.parseToJsonElement(overlay).jsonObject
        return JsonObject(settings + (DESKTOP_PICKER to picker)).toString()
    }
}

/** Claude Code's own /login and /logout, which sign in to Anthropic alone: off on the gateway, where /login is splice's. */
internal const val LOGIN_OFF: String = "DISABLE_LOGIN_COMMAND"
internal const val LOGOUT_OFF: String = "DISABLE_LOGOUT_COMMAND"

private const val REPLACE_BUILT_IN: String = "replaceBuiltInOptions"
