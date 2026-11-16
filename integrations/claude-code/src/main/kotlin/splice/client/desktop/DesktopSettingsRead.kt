// NEW: desktop-door (docs/specs/desktop-door.md, decisions 8 and 9). The one reader of the `claude` command's
// `settings.json`, for the daemon that writes it (`splice desktop on`, `off`, `rotate-key`) and for a launch of that
// command that lays its own settings over it. A blank file is an empty object; a file that isn't a JSON object, or
// can't be read, is said as such, so nothing writes over what the person wrote.
package splice.client.desktop

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.SafeFailureText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** What the settings file holds when it is read. */
public sealed class SettingsRead {
    public data object Absent : SettingsRead()

    public data class Held(val file: JsonObject) : SettingsRead()

    /** There, and not a JSON object splice can write into without losing what the person wrote: [why]. */
    public data class Unreadable(val why: String) : SettingsRead()
}

/** The settings file at [path]. */
public class DesktopSettingsRead(private val path: Path) {
    public fun read(): SettingsRead = try {
        val text = Files.readString(path)
        if (text.isBlank()) {
            SettingsRead.Held(JsonObject(emptyMap()))
        } else {
            (Json.parseToJsonElement(text) as? JsonObject)?.let { SettingsRead.Held(it) }
                ?: SettingsRead.Unreadable("$path is not a JSON object")
        }
    } catch (_: NoSuchFileException) {
        SettingsRead.Absent
    } catch (e: IOException) {
        SettingsRead.Unreadable("$path could not be read (${SafeFailureText.render(e)})")
    } catch (e: SerializationException) {
        SettingsRead.Unreadable("$path is not valid JSON (${SafeFailureText.render(e)})")
    }
}
