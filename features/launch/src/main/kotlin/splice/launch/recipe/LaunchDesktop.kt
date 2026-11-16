// NEW: desktop-door (docs/specs/desktop-door.md, decision 8). The terminal stays as it was while Claude Desktop's Code
// tab runs on splice. `splice desktop on` writes its switch into the settings file of the `claude` command, which a
// launch of that command reads too. So that launch lays its own settings over the file: every variable `on` wrote
// back to the launch's own value, or to what the file held before, or to nothing, and the picker the file held
// before. A pick made in the Code tab lands in the file's `model`; a selector there names a model of the door, which
// the `claude` command does not serve, so the launch starts on its head's own default and says so.
package splice.launch.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.client.desktop.DESKTOP_FILE_MODEL
import splice.client.desktop.DESKTOP_MODEL
import splice.client.desktop.DESKTOP_SETTINGS_FILE
import splice.client.desktop.DesktopSettings
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.client.desktop.providerSwitches
import splice.client.wrap.WrappedLaunch
import splice.core.util.JsonScalars
import java.nio.file.Path

/** Claude Desktop's door as a launch of the `claude` command sees it. The daemon's; assigned before routes serve. */
public interface LaunchDesktop {
    /** While Desktop is on, the record of what `splice desktop on` replaced in the settings file; null while off. */
    public fun replaced(): JsonObject?

    /** Whether [model] is a selector: a model ID with a head's discovery prefix, which only the door serves. */
    public fun isSelector(model: String): Boolean
}

/** What a launch over the `claude` command's folder lays over the settings file while Desktop is on, and what its
 *  warning says of it. */
internal data class DesktopCancelled(val overlay: JsonObject, val warning: String?) {
    /** The launch's own `--settings` JSON, [base], with this laid over it: its keys outrank the launch's. */
    fun over(base: String): String = JsonObject(Json.parseToJsonElement(base).jsonObject + overlay).toString()
}

internal class DesktopCancel(private val desktop: LaunchDesktop?) {
    private val settings = DesktopSettings()

    /** What every launch clears from the environment it inherits, with Desktop on or off: Claude Code's provider
     *  switches. A shell inside a Code tab session carries the door's, and a session that inherits any of them goes
     *  past its head. */
    val cleared: List<String> = providerSwitches

    /** For a launch over the `claude` command's folder, [wrapped], whose environment sets [env] and whose head
     *  starts on [pinned] when it pins one; null for any other launch, and while Desktop is off, when the launch is as
     *  it always was. */
    fun of(wrapped: WrappedLaunch?, env: Map<String, String>, pinned: String): DesktopCancelled? {
        val configDir = wrapped?.configDir ?: return null
        val replaced = desktop?.replaced() ?: return null
        val cancel = settings.cancel(replaced, env)
        val picked = fileModel(configDir)?.takeIf { desktop.isSelector(it) }
        val start = env[DESKTOP_MODEL] ?: pinned.ifBlank { CLAUDE_CODE_DEFAULT }
        return if (picked == null) DesktopCancelled(cancel, null) else started(cancel, picked, start)
    }

    /** [cancel] with the session started on [start] in place of [picked], a selector the file holds, and said so. */
    private fun started(cancel: JsonObject, picked: String, start: String): DesktopCancelled {
        val warning = "the settings file's model is $picked, a model of Claude Desktop's door that this command " +
            "doesn't serve, so the session starts on $start"
        return DesktopCancelled(JsonObject(cancel + (DESKTOP_FILE_MODEL to JsonPrimitive(start))), warning)
    }

    /** The `model` the settings file in [configDir] holds, or null when it holds none or can't be read. */
    private fun fileModel(configDir: Path): String? {
        val read = DesktopSettingsRead(configDir.resolve(DESKTOP_SETTINGS_FILE)).read()
        return JsonScalars.str((read as? SettingsRead.Held)?.file, DESKTOP_FILE_MODEL)
    }
}

// Claude Code's own word for the model it recommends, which a launch whose head names no model starts on.
private const val CLAUDE_CODE_DEFAULT = "default"
