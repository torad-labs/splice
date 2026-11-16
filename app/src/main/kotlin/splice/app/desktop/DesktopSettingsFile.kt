// NEW: desktop-door (docs/specs/desktop-door.md, decision 9). The one file splice writes in the `claude` command's own
// folder: `~/.claude/settings.json`, which the Code tab's Claude Code and the terminal's `claude` both read. A stated
// exception to head isolation, and written only at the person's command (`splice desktop on`, `off`, `rotate-key`) or,
// while Desktop is on, to keep its lineup following splice. A file linked from elsewhere (a dotfiles repo) is written
// where the link points, so the link stays. It holds the desktop key, so it is written owner-only.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package splice.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.desktop.DESKTOP_SETTINGS_FILE
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.core.config.UserHome
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

internal class DesktopSettingsFile(val path: Path = UserHome.claudeDir().resolve(DESKTOP_SETTINGS_FILE)) {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun read(): SettingsRead = DesktopSettingsRead(path).read()

    /** [file], written whole and atomically, as Claude Code writes the file: two spaces, a newline at the end. */
    fun write(file: JsonObject) {
        SecureFile.writeAtomic0600(target(), json.encodeToString(JsonObject.serializer(), file) + "\n")
    }

    /** The file taken away, when nothing is left of one that was not there before splice wrote it. */
    fun remove() {
        Files.deleteIfExists(target())
    }

    /** Where a write lands: through a link to the file it names, so the link is kept. */
    private fun target(): Path = if (Files.isSymbolicLink(path)) path.toRealPath() else path
}
