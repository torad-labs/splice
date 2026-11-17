// NEW: door-default (docs/specs/door-default.md, decisions 2, 4 and 5). What a terminal `claude` is launched with to
// run on splice's door: Claude Code's Mantle switch at the door, the terminal's own key, the window the door declares,
// the model the session starts on, its own /login and /logout off, and nothing that would send it anywhere else. The
// session runs over the person's own Claude folder, so no CLAUDE_CONFIG_DIR is set and nothing of the person's is
// changed there (splice's /login, DoorLogin, is the one file it lays): the picker rides the launch's `--settings`
// overlay as `modelPicker`, with `replaceBuiltInOptions` so the picker lists those rows in place of Claude Code's own,
// and so does the hook that answers /login. Facts of Claude Code's dialect, read on 2.1.325 (desktop-door.md's table).
package splice.client.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.client.ClaudeTiers
import splice.client.Keys

/** One row of a terminal session's picker: the selector it sends, the model's own label, and the head's command. */
public data class DoorPickerRow(val model: String, val label: String, val command: String)

/** A launch of `claude` on the door: [door] its address, [key] the terminal's key, [window] the window it declares,
 *  [model] the selector a session starts on (null with no head in the door), and [rows] its picker, empty when no
 *  account is signed in, so Claude Code lists its own models, which the door runs as tiers. */
public data class DoorSession(
    val door: String,
    val key: String,
    val window: Long,
    val model: String?,
    val rows: List<DoorPickerRow>,
) {
    /** What the launch sets. */
    public fun env(): Map<String, String> = buildMap {
        putAll(DesktopEnvironment().of(door, key, window))
        model?.let { put(DESKTOP_MODEL, it) }
        put(LOGIN_OFF, "1")
        put(LOGOUT_OFF, "1")
    }

    /** What the launch takes out of the environment it inherits: every other way to a provider, the tiers and the
     *  models an outer head's session plants, and a starting model when the door names none. */
    public fun unset(): List<String> = buildList {
        addAll(elsewhere)
        addAll(planted)
        if (model == null) add(DESKTOP_MODEL)
    }

    /** The `--settings` overlay: the picker, when there are rows, splice's launch mark where [marked], and [hooks] by
     *  event (splice's /login, DoorLogin), which Claude Code runs beside the person's own. */
    public fun overlay(
        marked: Boolean,
        markKey: String,
        hooks: Map<String, List<JsonObject>> = emptyMap(),
    ): String = buildJsonObject {
        if (rows.isNotEmpty()) {
            putJsonObject(DESKTOP_PICKER) {
                put(REPLACE_BUILT_IN, true)
                putJsonArray(PICKER_OPTIONS) {
                    rows.forEach { row ->
                        addJsonObject {
                            put("model", row.model)
                            put("label", row.label)
                            put("description", "splice · ${row.command}")
                        }
                    }
                }
            }
        }
        if (marked) put(markKey, true)
        if (hooks.isNotEmpty()) put(Keys.HOOKS, JsonObject(hooks.mapValues { (_, entries) -> JsonArray(entries) }))
    }.toString()
}

/** Claude Code's own /login and /logout, which sign in to Anthropic alone: off on the door, where /login is splice's. */
private const val LOGIN_OFF = "DISABLE_LOGIN_COMMAND"
private const val LOGOUT_OFF = "DISABLE_LOGOUT_COMMAND"

private const val REPLACE_BUILT_IN = "replaceBuiltInOptions"

/** Everything that would send a session past the door: a base URL, an Anthropic key or token, and every other
 *  provider switch a launch clears (providerSwitches), less the Mantle switch, its address and its key, which are the
 *  door's own and set. */
private val elsewhere: List<String> = listOf(
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "CLAUDE_CODE_OAUTH_TOKEN",
    "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY",
) + (providerSwitches - setOf(DESKTOP_USE_MANTLE, DESKTOP_MANTLE_URL, DESKTOP_BEARER))

/** What a head's launch plants for its own models and window, which a session started inside one would carry to the
 *  door, where no head's own model IDs are selectors: the tiers, the small model and the compaction window. */
private val planted: List<String> = ClaudeTiers.names.flatMap(ClaudeTiers::variables) +
    listOf("ANTHROPIC_SMALL_FAST_MODEL", "CLAUDE_CODE_AUTO_COMPACT_WINDOW", "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE")
