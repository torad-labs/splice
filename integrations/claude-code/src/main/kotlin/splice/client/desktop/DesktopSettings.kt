// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4 and 8). Claude Code's own settings file as the door reads
// it. Since 0.6.1 the door's variables live in Claude Desktop's own environment store (DesktopEnvironment) and splice
// writes nothing into `~/.claude/settings.json`, which every terminal session hot-reloads. What is left here: why the
// file would send the Code tab past the door (a provider switch of the person's own), taking out what an older
// splice's `splice desktop on` wrote there (its `env`, its picker, and a selector the Code tab picked into `model`),
// and the file as a head with a config folder of its own inherits it. A fact of Claude Code's own file, so it lives
// here; reading and writing the file is the daemon's.
package splice.client.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.Keys
import splice.core.util.JsonScalars

/** Whether a model ID is a selector: a model of one of splice's heads, which only the door serves. */
public fun interface DoorSelectors {
    public operator fun invoke(model: String): Boolean
}

/** The settings file as the door reads it, and as it takes an older splice's door out of it. */
public class DesktopSettings {
    /** Why [file] would send the Code tab past the door, whatever Desktop's own store holds: a provider switch of the
     *  person's own, another provider's or Mantle at an address no desktop key opens, or an `env` Claude Code can't
     *  read. Null when it sends it nowhere. An older splice's door in it is no reason: it is taken out
     *  ([withoutDoor]). */
    public fun refusal(file: JsonObject): String? {
        val env = file[ENV] ?: return null
        if (env !is JsonObject) return "its \"env\" is not an object"
        val other = otherProviders.firstOrNull { switched(env, it) }
        val mantle = JsonScalars.str(env, DESKTOP_MANTLE_URL) ?: "(unset)"
        return when {
            other != null ->
                "it sets $other=${JsonScalars.str(env, other)}, which sends the Code tab to another provider"
            switched(env, DESKTOP_USE_MANTLE) && !holdsDoor(file) ->
                "it sets $DESKTOP_USE_MANTLE with $DESKTOP_MANTLE_URL=$mantle, which is not splice's door"
            else -> null
        }
    }

    /** Whether [file] holds what an older splice's `splice desktop on` wrote there: Mantle switched on with a desktop
     *  key, at whatever address that splice's door had. */
    public fun holdsDoor(file: JsonObject): Boolean {
        val env = file[ENV] as? JsonObject ?: return false
        val key = JsonScalars.str(env, DESKTOP_BEARER).orEmpty()
        return switched(env, DESKTOP_USE_MANTLE) && key.startsWith(DESKTOP_KEY_PREFIX)
    }

    /** [file] with an older splice's door taken out: what its `on` wrote, with what that replaced put back by
     *  [replaced] (with no record, only what it wrote is taken out), and a `model` the Code tab picked from its door,
     *  one of its picker's rows or one of [selectors], which no terminal session serves. Null when nothing is left of
     *  a file that was not there before that `on`; [file] itself when none of it is there. */
    public fun withoutDoor(file: JsonObject, replaced: JsonObject?, selectors: DoorSelectors): JsonObject? {
        val out = LinkedHashMap<String, JsonElement>(file)
        if (pickedFromDoor(file, selectors)) out.remove(DESKTOP_FILE_MODEL)
        if (!holdsDoor(file)) return JsonObject(out)
        (file[ENV] as? JsonObject)?.let { env ->
            val restored = restoredEnv(env, replaced?.get(ENV) as? JsonObject)
            if (restored.isEmpty() && !existed(replaced, ENV_EXISTED)) out.remove(ENV) else out[ENV] = restored
        }
        val picker = replaced?.get(PICKER)?.takeIf { it !is JsonNull }
        if (picker == null) out.remove(PICKER) else out[PICKER] = picker
        return if (out.isEmpty() && !existed(replaced, EXISTED)) null else JsonObject(out)
    }

    /** Whether [file]'s `model` was picked from a door: one of its picker's rows, or one of [selectors]. */
    private fun pickedFromDoor(file: JsonObject, selectors: DoorSelectors): Boolean {
        val picked = JsonScalars.str(file, DESKTOP_FILE_MODEL) ?: return false
        return selectors(picked) || picked in rows(file)
    }

    /** The models [file]'s picker lists. */
    private fun rows(file: JsonObject): List<String> =
        ((file[PICKER] as? JsonObject)?.get(OPTIONS) as? JsonArray).orEmpty()
            .mapNotNull { JsonScalars.str(it as? JsonObject, DESKTOP_FILE_MODEL) }

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

    /** [file] as a head with a config folder of its own inherits it: no provider switch, which would send the head's
     *  sessions past it, and while the door is in it, nothing `on` wrote there, its picker included, nor a `model` that
     *  is one of the picker's rows, picked in the Code tab. */
    public fun inherited(file: JsonObject): JsonObject {
        val env = file[ENV] as? JsonObject ?: return file
        val door = holdsDoor(file)
        val dropped = if (door) providerSwitches + desktopEnv else providerSwitches
        val out = JsonObject(file + (ENV to JsonObject(env - dropped.toSet())))
        if (!door) return out
        val picked = JsonScalars.str(file, DESKTOP_FILE_MODEL) in rows(file)
        return JsonObject(if (picked) out - PICKER - DESKTOP_FILE_MODEL else out - PICKER)
    }

    /** Whether [name] is set in [env] as Claude Code reads a switch: any value but empty, `0` or `false`. */
    private fun switched(env: JsonObject, name: String): Boolean {
        val value = (env[name] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return false
        return value.isNotEmpty() && value != "0" && value != "false"
    }
}

/** The Mantle switch, its base URL and its bearer: the door, and the desktop key. */
internal const val DESKTOP_USE_MANTLE: String = "CLAUDE_CODE_USE_MANTLE"
internal const val DESKTOP_MANTLE_URL: String = "ANTHROPIC_BEDROCK_MANTLE_BASE_URL"
internal const val DESKTOP_BEARER: String = "AWS_BEARER_TOKEN_BEDROCK"

/** The one window the Code tab's Claude Code holds for a model it does not know. */
internal const val DESKTOP_WINDOW: String = "CLAUDE_CODE_MAX_CONTEXT_TOKENS"

/** Auto mode's classifier server, which runs on claude.ai and is no part of a provider; off. */
internal const val DESKTOP_AUTO_MODE: String = "CLAUDE_CODE_AUTO_MODE_SERVER"

/** The model a session starts on, which an older splice's `on` wrote. */
internal const val DESKTOP_MODEL: String = "ANTHROPIC_MODEL"

/** Every variable an older splice's `on` wrote into the file's `env`. */
private val desktopEnv: List<String> =
    listOf(DESKTOP_USE_MANTLE, DESKTOP_MANTLE_URL, DESKTOP_BEARER, DESKTOP_WINDOW, DESKTOP_AUTO_MODE, DESKTOP_MODEL)

/** The other providers' switches: a file that sets one sends the Code tab elsewhere, and `on` refuses it. */
private val otherProviders: List<String> =
    listOf("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")

/** Claude Code's provider switches and what goes with them, which a launch clears from the environment it inherits:
 *  any of them reaching a session sends it past its head, and a shell inside a Code tab session carries the door's. */
public val providerSwitches: List<String> =
    otherProviders + listOf(DESKTOP_USE_MANTLE, "ANTHROPIC_BEDROCK_BASE_URL", DESKTOP_MANTLE_URL, DESKTOP_BEARER)

/** What every desktop key begins with, so a file that holds one is known to hold the door. */
public const val DESKTOP_KEY_PREFIX: String = "splice-desktop-"

/** The file's key for Claude Code's picker, which an older splice's `on` wrote. */
internal const val DESKTOP_PICKER: String = "modelPicker"

/** The file's key for the model the person picked, in the Code tab or in Claude Code. */
internal const val DESKTOP_FILE_MODEL: String = Keys.MODEL

private const val ENV = "env"
private const val PICKER = DESKTOP_PICKER
private const val OPTIONS = "options"

// The record's own keys: whether the file and its env block were there before `on`.
private const val EXISTED = "file_existed"
private const val ENV_EXISTED = "env_existed"

/** The settings file's name in the `claude` command's own folder. */
public const val DESKTOP_SETTINGS_FILE: String = Keys.SETTINGS_FILE_NAME
