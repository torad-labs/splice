// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). What `splice desktop on` puts in Claude Code's own
// settings file, and how `off` takes it out again. The Code tab of Claude Desktop runs its own Claude Code, which reads
// its provider switches from the `env` block of `~/.claude/settings.json` and its picker from `modelPicker`. Read on
// Nov 16, 2026 from Claude Code 2.1.325: Mantle mode needs `CLAUDE_CODE_USE_MANTLE=1`, the base URL and a bearer, and a
// `modelPicker` with `replaceBuiltInOptions` lists its rows under their labels. A fact of Claude Code's own file, so it
// lives here; reading and writing the file is the daemon's.
//
// Everything else in the file stays as the person wrote it, `model` above all: it holds the pick they made. What `on`
// replaces is recorded, so `off` puts it back and a file that `on` then `off` touched reads as it did before.
package splice.client.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.client.Keys
import splice.core.util.JsonScalars

/** One row of the Code tab's picker. */
public data class DesktopRow(val model: String, val label: String, val description: String)

/** What `on` writes: the door's address, the desktop key, the declared window, the default model's selector and the
 *  picker's rows. */
public data class DesktopWrite(
    val door: String,
    val key: String,
    val window: Long,
    val model: String,
    val rows: List<DesktopRow>,
)

/** A settings file once `on` has written into it, and the record of what it replaced there. */
public data class DesktopOn(val file: JsonObject, val replaced: JsonObject)

/** The settings file's `env` and `modelPicker`, as `splice desktop` writes and restores them. */
public class DesktopSettings {
    /** Why `on` must not write into [file] (a provider switch set to anything but the door at [door], or a file not
     *  shaped as Claude Code reads it), or null when it may. */
    public fun refusal(file: JsonObject, door: String): String? {
        val env = file[ENV] ?: return null
        if (env !is JsonObject) return "its \"env\" is not an object"
        val other = otherProviders.firstOrNull { switched(env, it) }
        val mantle = JsonScalars.str(env, DESKTOP_MANTLE_URL)
        return when {
            other != null ->
                "it sets $other=${JsonScalars.str(env, other)}, which points Claude Code at another provider"
            switched(env, DESKTOP_USE_MANTLE) && mantle != door ->
                "it sets $DESKTOP_USE_MANTLE with $DESKTOP_MANTLE_URL=${mantle ?: "(unset)"}, " +
                    "which is not splice's door"
            else -> null
        }
    }

    /** Whether [file] has the door at [door] switched on, as `on` left it. */
    public fun pointsAt(file: JsonObject, door: String): Boolean {
        val env = file[ENV] as? JsonObject ?: return false
        return switched(env, DESKTOP_USE_MANTLE) && JsonScalars.str(env, DESKTOP_MANTLE_URL) == door
    }

    /** [file] (null: there is none) with [write] in it. [replaced] is the record of an earlier `on` that no `off`
     *  has undone, which is kept: what the person had is what that one replaced. */
    public fun on(file: JsonObject?, write: DesktopWrite, replaced: JsonObject?): DesktopOn {
        val had = file ?: JsonObject(emptyMap())
        val env = had[ENV] as? JsonObject
        val record = replaced ?: buildJsonObject {
            put(EXISTED, file != null)
            put(ENV_EXISTED, env != null)
            putJsonObject(ENV) { desktopEnv.forEach { name -> put(name, env?.get(name) ?: JsonNull) } }
            put(PICKER, had[PICKER] ?: JsonNull)
        }
        val values = mapOf(
            DESKTOP_USE_MANTLE to "1",
            DESKTOP_MANTLE_URL to write.door,
            DESKTOP_BEARER to write.key,
            DESKTOP_WINDOW to write.window.toString(),
            DESKTOP_AUTO_MODE to "0",
            DESKTOP_MODEL to write.model,
        )
        val newEnv = LinkedHashMap<String, JsonElement>(env.orEmpty())
        values.forEach { (name, value) -> newEnv[name] = JsonPrimitive(value) }
        val out = LinkedHashMap<String, JsonElement>(had)
        out[ENV] = JsonObject(newEnv)
        out[PICKER] = picker(write.rows)
        return DesktopOn(JsonObject(out), record)
    }

    /** [file] with its picker rows replaced by [rows], and nothing else changed: the lineup following splice. */
    public fun follow(file: JsonObject, rows: List<DesktopRow>): JsonObject =
        JsonObject(file + (PICKER to picker(rows)))

    /** [file] with the desktop key it holds replaced by [key]. */
    public fun rekeyed(file: JsonObject, key: String): JsonObject {
        val env = file[ENV] as? JsonObject ?: return file
        return JsonObject(file + (ENV to JsonObject(env + (DESKTOP_BEARER to JsonPrimitive(key)))))
    }

    /** [file] with what `on` wrote taken out and what it replaced, by [replaced], put back; null when nothing is left
     *  of a file that was not there before `on`. With no record, only what `on` wrote is taken out. */
    public fun off(file: JsonObject, replaced: JsonObject?): JsonObject? {
        val out = LinkedHashMap<String, JsonElement>(file)
        (file[ENV] as? JsonObject)?.let { env ->
            val restored = restoredEnv(env, replaced?.get(ENV) as? JsonObject)
            if (restored.isEmpty() && !existed(replaced, ENV_EXISTED)) out.remove(ENV) else out[ENV] = restored
        }
        val picker = replaced?.get(PICKER)?.takeIf { it !is JsonNull }
        if (picker == null) out.remove(PICKER) else out[PICKER] = picker
        return if (out.isEmpty() && !existed(replaced, EXISTED)) null else JsonObject(out)
    }

    /** [env] with every variable `on` wrote taken out, and put back as [was] held it before `on`. */
    private fun restoredEnv(env: JsonObject, was: JsonObject?): JsonObject {
        val restored = LinkedHashMap<String, JsonElement>(env)
        desktopEnv.forEach { name ->
            val old = was?.get(name)?.takeIf { it !is JsonNull }
            if (old == null) restored.remove(name) else restored[name] = old
        }
        return JsonObject(restored)
    }

    /** Whether the record [replaced] says what [flag] names was there before `on`; with no record, it was. */
    private fun existed(replaced: JsonObject?, flag: String): Boolean =
        replaced == null || JsonScalars.str(replaced, flag) != "false"

    /** What a launch of the `claude` command lays over the file while `on` holds it, as its `--settings` outrank the
     *  file: every variable `on` wrote set to [own], the launch's own value, else to what the file held before `on`
     *  by [replaced], else to the empty string, which Claude Code reads as unset; and the picker the file held before
     *  `on`, or one with no rows, which lists the built-in models. */
    public fun cancel(replaced: JsonObject?, own: Map<String, String>): JsonObject {
        val was = replaced?.get(ENV) as? JsonObject
        val theirs = replaced?.get(PICKER) as? JsonObject ?: JsonObject(emptyMap())
        return buildJsonObject {
            putJsonObject(ENV) {
                desktopEnv.forEach { name ->
                    val before = was?.get(name)?.takeIf { it !is JsonNull }
                    put(name, own[name]?.let(::JsonPrimitive) ?: before ?: JsonPrimitive(""))
                }
            }
            val none = mapOf(REPLACE_BUILT_IN to JsonPrimitive(false), OPTIONS to JsonArray(emptyList()))
            put(PICKER, JsonObject(none + theirs))
        }
    }

    /** [file] as a head with a config folder of its own inherits it: no provider switch, which would send the head's
     *  sessions past it, and while the door is in it, nothing `on` wrote there, its picker included, nor a `model` that
     *  is one of the picker's rows, picked in the Code tab. */
    public fun inherited(file: JsonObject): JsonObject {
        val env = file[ENV] as? JsonObject ?: return file
        val key = JsonScalars.str(env, DESKTOP_BEARER).orEmpty()
        val door = switched(env, DESKTOP_USE_MANTLE) && key.startsWith(DESKTOP_KEY_PREFIX)
        val dropped = if (door) providerSwitches + desktopEnv else providerSwitches
        val out = JsonObject(file + (ENV to JsonObject(env - dropped.toSet())))
        if (!door) return out
        val rows = ((file[PICKER] as? JsonObject)?.get(OPTIONS) as? JsonArray).orEmpty()
            .mapNotNull { JsonScalars.str(it as? JsonObject, DESKTOP_FILE_MODEL) }
        val picked = JsonScalars.str(file, DESKTOP_FILE_MODEL) in rows
        return JsonObject(if (picked) out - PICKER - DESKTOP_FILE_MODEL else out - PICKER)
    }

    private fun picker(rows: List<DesktopRow>): JsonObject = buildJsonObject {
        put(REPLACE_BUILT_IN, true)
        putJsonArray(OPTIONS) {
            rows.forEach { row ->
                addJsonObject {
                    put("model", row.model)
                    put("label", row.label)
                    put("description", row.description)
                }
            }
        }
    }

    /** Whether [name] is set in [env] as Claude Code reads a switch: any value but empty, `0` or `false`. */
    private fun switched(env: JsonObject, name: String): Boolean {
        val value = (env[name] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return false
        return value.isNotEmpty() && value != "0" && value != "false"
    }
}

/** The Mantle switch, its base URL and its bearer: the door, and the desktop key. */
public const val DESKTOP_USE_MANTLE: String = "CLAUDE_CODE_USE_MANTLE"
public const val DESKTOP_MANTLE_URL: String = "ANTHROPIC_BEDROCK_MANTLE_BASE_URL"
public const val DESKTOP_BEARER: String = "AWS_BEARER_TOKEN_BEDROCK"

/** The one window the Code tab's Claude Code holds for a model it does not know. */
public const val DESKTOP_WINDOW: String = "CLAUDE_CODE_MAX_CONTEXT_TOKENS"

/** Auto mode's classifier server, which runs on claude.ai and is no part of a provider; off. */
public const val DESKTOP_AUTO_MODE: String = "CLAUDE_CODE_AUTO_MODE_SERVER"

/** The model a session starts on. */
public const val DESKTOP_MODEL: String = "ANTHROPIC_MODEL"

/** Every variable `on` writes into the file's `env`, in the order it writes them. */
public val desktopEnv: List<String> =
    listOf(DESKTOP_USE_MANTLE, DESKTOP_MANTLE_URL, DESKTOP_BEARER, DESKTOP_WINDOW, DESKTOP_AUTO_MODE, DESKTOP_MODEL)

/** The other providers' switches: a file that sets one is pointed elsewhere, and `on` leaves it alone. */
public val otherProviders: List<String> =
    listOf("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")

/** Claude Code's provider switches and what goes with them, which a launch clears from the environment it inherits:
 *  any of them reaching a session sends it past its head, and a shell inside a Code tab session carries the door's. */
public val providerSwitches: List<String> =
    otherProviders + listOf(DESKTOP_USE_MANTLE, "ANTHROPIC_BEDROCK_BASE_URL", DESKTOP_MANTLE_URL, DESKTOP_BEARER)

/** What every desktop key begins with, so a file that holds one is known to hold the door. */
public const val DESKTOP_KEY_PREFIX: String = "splice-desktop-"

/** The file's key for Claude Code's picker. */
public const val DESKTOP_PICKER: String = "modelPicker"

/** The file's key for the model the person picked, in the Code tab or in Claude Code. */
public const val DESKTOP_FILE_MODEL: String = Keys.MODEL

private const val ENV = "env"
private const val PICKER = DESKTOP_PICKER
private const val OPTIONS = "options"
private const val REPLACE_BUILT_IN = "replaceBuiltInOptions"

// The record's own keys: whether the file and its env block were there before `on`.
private const val EXISTED = "file_existed"
private const val ENV_EXISTED = "env_existed"

/** The settings file's name in the `claude` command's own folder, the one file `splice desktop` writes there. */
public const val DESKTOP_SETTINGS_FILE: String = Keys.SETTINGS_FILE_NAME
