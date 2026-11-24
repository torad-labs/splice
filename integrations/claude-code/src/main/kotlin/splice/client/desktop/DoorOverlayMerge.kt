// NEW: custom-provider (issue #680, cp-overlay). Claude Code takes one `--settings` per launch, so a `--settings` a
// person or a team's wrapper passes to `claude` on the door either replaced splice's overlay (losing the picker and the
// /login hook) or was lost. A launch on the door now reads every `--settings` among the person's arguments, inline JSON
// or a file, `--settings X` and `--settings=X` alike, and hands Claude Code one: splice's overlay with theirs merged in.
//
// The rule: objects merge key by key and the person's values win, several `--settings` in the order given; hooks are
// concatenated per event, splice's entries first, so a wrapper's hook runs beside splice's /login hook; and what keeps
// the session on the door and as its launch set it (#791: the gateway's address and key, the starting model, the
// windows, the client's timers, NO_PROXY, every provider switch off and every other credential and model list empty,
// Claude Code's own /login and /logout off) and splice's picker are always splice's. Every other key, a wrapper's
// `statusLine` among them, is the person's, but for a custom header in its `env`, which joins the launch's own headers
// in its environment instead of standing over them. Claude Code's setting names stay here, beside the
// other Claude Code facts (DoorSession).
package splice.client.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.Keys
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.sessions.registry.LaunchMarker
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The person's `--settings` merged into splice's door [overlay]. [cwd] is where the person ran the command, which a
 *  relative settings file is read against. [held]: what this launch adds to the overlay's `env` beside what [overlay]
 *  carries (#791: the client's timers, the windows, NO_PROXY), none of it a secret. */
public class DoorOverlayMerge(
    private val overlay: String,
    private val cwd: String?,
    private val held: Map<String, String> = emptyMap(),
) {
    /** What a launch with [args] hands Claude Code: one overlay and the other arguments, in their order; or why it
     *  cannot start. */
    public sealed class Merged {
        /** [headers]: the ANTHROPIC_CUSTOM_HEADERS the person's `--settings` named, taken out of [overlay], where it
         *  would outrank the launch's own (its client wall), for the launch to set beside them (#791). */
        public data class Ready(val overlay: String, val args: List<String>, val headers: String? = null) : Merged()

        public data class Refused(val reason: String) : Merged()
    }

    public fun merge(args: List<String>): Merged {
        val given = SettingsArgs(args)
        if (given.missing) {
            return Merged.Refused(
                "${LaunchMarker.SETTINGS_FLAG} names no settings; give it a JSON object or a file holding one",
            )
        }
        val theirs = given.values.map { value -> readOrNull(value) ?: return Merged.Refused(refusal(value)) }
        val own = withHeld(Json.parseToJsonElement(overlay) as JsonObject)
        val person = theirs.fold(JsonObject(emptyMap())) { merged, next -> merged(merged, next) }
        val headers = JsonScalars.str(person[ENV_KEY] as? JsonObject, GATEWAY_HEADERS)
        return Merged.Ready(kept(own, merged(own, person)).toString(), given.rest, headers)
    }

    /** The person's arguments, sorted: each `--settings X` or `--settings=X` value, in order, and every other argument,
     *  in order. [missing]: a `--settings` ends them with no value after it. */
    private class SettingsArgs(args: List<String>) {
        val values = mutableListOf<String>()
        val rest = mutableListOf<String>()
        var missing = false
            private set

        init {
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg == LaunchMarker.SETTINGS_FLAG -> args.getOrNull(++i)?.let(values::add) ?: run { missing = true }
                    arg.startsWith(SETTINGS_EQUALS) -> values += arg.removePrefix(SETTINGS_EQUALS)
                    else -> rest += arg
                }
                i++
            }
        }
    }

    /** [value] as a settings object, or null when it is not one or its file cannot be read. */
    private fun readOrNull(value: String): JsonObject? =
        Cancellables.runCatchingCancellable { read(value) }.fold(onSuccess = { it }, onFailure = { null })

    /** [value] as a settings object: inline JSON, or the file it names; null when it holds something else, and a throw
     *  when the file cannot be read or the text is not JSON. */
    private fun read(value: String): JsonObject? {
        val text = if (value.trimStart().startsWith("{")) value else Files.readString(fileOf(value))
        return Json.parseToJsonElement(text) as? JsonObject
    }

    private fun fileOf(value: String): Path {
        val path = Paths.get(UserHome.expand(value))
        return if (path.isAbsolute || cwd == null) path else Paths.get(cwd).resolve(path)
    }

    /** Says what is wrong and where, as boot does, without repeating inline JSON, which can hold a wrapper's secrets. */
    private fun refusal(value: String): String = if (value.trimStart().startsWith("{")) {
        "the inline JSON after ${LaunchMarker.SETTINGS_FLAG} is not a JSON object; nothing was launched"
    } else {
        "${LaunchMarker.SETTINGS_FLAG} $value is neither a JSON object nor a readable file holding one; " +
            "nothing was launched"
    }

    /** [over] merged into [base]: objects key by key with [over]'s values winning, and hooks concatenated per event. */
    private fun merged(base: JsonObject, over: JsonObject): JsonObject {
        val out = base.toMutableMap()
        for ((key, value) in over) {
            val before = out[key]
            out[key] = when {
                key == Keys.HOOKS && before is JsonObject && value is JsonObject -> hooks(before, value)
                before is JsonObject && value is JsonObject -> merged(before, value)
                else -> value
            }
        }
        return JsonObject(out)
    }

    /** Each event's entries, [base]'s first, then [over]'s. */
    private fun hooks(base: JsonObject, over: JsonObject): JsonObject {
        val out = base.toMutableMap()
        for ((event, entries) in over) {
            val before = out[event]
            out[event] = if (before is JsonArray && entries is JsonArray) JsonArray(before + entries) else entries
        }
        return JsonObject(out)
    }

    /** [own] with [held] in its `env`. */
    private fun withHeld(own: JsonObject): JsonObject {
        if (held.isEmpty()) return own
        val env = (own[ENV_KEY] as? JsonObject).orEmpty() + held.mapValues { (_, value) -> JsonPrimitive(value) }
        return JsonObject(own + (ENV_KEY to JsonObject(env)))
    }

    /** [merged] with splice's own values back where only splice's may stand: every variable splice's overlay carries in
     *  `env` (#791: what the launch sets, and what it takes out spelled empty), and the picker. A door variable splice
     *  sets in the launch's environment rather than here is taken out of [merged]'s `env`, so the launch's value holds.
     *  Every other key the person gave, a `statusLine` among them, stays as they gave it. */
    private fun kept(own: JsonObject, merged: JsonObject): JsonObject {
        val out = merged.toMutableMap()
        val theirs = merged[ENV_KEY] as? JsonObject
        val ownEnv = (own[ENV_KEY] as? JsonObject).orEmpty()
        if (theirs != null || ownEnv.isNotEmpty()) out[ENV_KEY] = JsonObject(theirs.orEmpty() - doorVariables + ownEnv)
        restore(out, own, DESKTOP_PICKER)
        return JsonObject(out)
    }

    private fun restore(out: MutableMap<String, JsonElement>, own: JsonObject, key: String) {
        val mine = own[key]
        if (mine == null) out.remove(key) else out[key] = mine
    }
}

private const val SETTINGS_EQUALS = "${LaunchMarker.SETTINGS_FLAG}="
private const val ENV_KEY = "env"

/** What keeps a session on the door: the variables a person's settings may never move. */
private val doorVariables: Set<String> =
    setOf(
        DESKTOP_USE_MANTLE,
        DESKTOP_MANTLE_URL,
        DESKTOP_BEARER,
        DESKTOP_MODEL,
        DESKTOP_WINDOW,
        LOGIN_OFF,
        LOGOUT_OFF,
        GATEWAY_TOKEN,
        GATEWAY_HEADERS,
    )
