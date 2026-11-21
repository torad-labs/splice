// NEW: custom-provider (issue #680, cp-overlay). Claude Code takes one `--settings` per launch, so a `--settings` a
// person or a team's wrapper passes to `claude` on the door either replaced splice's overlay (losing the picker and the
// /login hook) or was lost. A launch on the door now reads every `--settings` among the person's arguments, inline JSON
// or a file, `--settings X` and `--settings=X` alike, and hands Claude Code one: splice's overlay with theirs merged in.
//
// The rule: objects merge key by key and the person's values win, several `--settings` in the order given; hooks are
// concatenated per event, splice's entries first, so a wrapper's hook runs beside splice's /login hook; and what keeps
// the session on the door (the Mantle switch, its address and key, the starting model, the window, Claude Code's own
// /login and /logout off) and splice's picker are always splice's. Claude Code's setting names stay here, beside the
// other Claude Code facts (DoorSession).
package splice.client.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.client.Keys
import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.sessions.registry.LaunchMarker
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The person's `--settings` merged into splice's door [overlay]. [cwd] is where the person ran the command, which a
 *  relative settings file is read against. */
public class DoorOverlayMerge(private val overlay: String, private val cwd: String?) {
    /** What a launch with [args] hands Claude Code: one overlay and the other arguments, in their order; or why it
     *  cannot start. */
    public sealed class Merged {
        public data class Ready(val overlay: String, val args: List<String>) : Merged()

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
        val own = Json.parseToJsonElement(overlay) as JsonObject
        val person = theirs.fold(JsonObject(emptyMap())) { merged, next -> merged(merged, next) }
        return Merged.Ready(kept(own, merged(own, person)).toString(), given.rest)
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

    /** [merged] with splice's own values back where only splice's may stand: the door's variables in `env` and the
     *  picker. A variable splice sets in the launch's environment rather than here is taken out of [merged]'s `env`,
     *  so the launch's value holds. */
    private fun kept(own: JsonObject, merged: JsonObject): JsonObject {
        val out = merged.toMutableMap()
        (merged[ENV_KEY] as? JsonObject)?.let { theirs ->
            val ownEnv = own[ENV_KEY] as? JsonObject ?: JsonObject(emptyMap())
            out[ENV_KEY] = JsonObject(
                theirs - doorVariables + doorVariables.mapNotNull { name ->
                    ownEnv[name]?.let {
                        name to it
                    }
                },
            )
        }
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
    setOf(DESKTOP_USE_MANTLE, DESKTOP_MANTLE_URL, DESKTOP_BEARER, DESKTOP_MODEL, DESKTOP_WINDOW, LOGIN_OFF, LOGOUT_OFF)
