// NEW: desktop-door (docs/specs/desktop-door.md, decision 8). What an older splice's `splice desktop on` wrote into
// `~/.claude/settings.json` (0.6.0 put the door's switch, address and key there), taken out at each start, and by `on`
// and `off`, with what it replaced put back. Only that door's own values go: a model the person picked themselves stays
// (DesktopSettings.withoutDoor). Split from DesktopSwitch, which no longer writes the file.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonObject
import splice.app.desktop.record.DesktopRecords
import splice.client.desktop.DesktopSettings
import splice.client.desktop.DoorSelectors
import splice.client.desktop.SettingsRead
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText

internal class DesktopSettingsTidy(
    private val file: DesktopSettingsFile,
    private val records: DesktopRecords,
    private val selectors: DoorSelectors,
    private val log: LogSink,
) {
    private val settings = DesktopSettings()

    /** At a start: the file tidied, said in the log. A file splice can't read or write is said there too. */
    fun atStart() {
        Cancellables.runCatchingCancellable { tidied() }
            .onSuccess { if (it.isNotEmpty()) log("[desktop] ${it.removePrefix(". ")}\n") }
            .onFailure { log("[desktop] ${file.path} could not be tidied: ${SafeFailureText.render(it)}\n") }
    }

    /** What an older splice's `on` wrote into the settings file taken out, and a sentence saying so, empty when there
     *  was none. A file splice can't read is said in the sentence and left as it is, and so is one Claude Code wrote
     *  each time splice looked. */
    fun tidied(): String = (1..LOOKS).firstNotNullOfOrNull { looked() }
        ?: ". ${file.path} changed each time splice looked, so what an older splice wrote there may still be in it"

    /** One look at the settings file: the sentence, or null when Claude Code wrote the file while the old door was
     *  being taken out of it, so it is looked at again. */
    private fun looked(): String? = when (val read = file.read()) {
        is SettingsRead.Unreadable -> ". The old door's values could not be looked for: ${read.why}"
        SettingsRead.Absent -> "".also { spent() }
        is SettingsRead.Held -> tidied(read.file)
    }

    /** [held], the settings file, with what an older splice's `on` wrote taken out, and a sentence saying so; null
     *  when the file changed before it could be written. */
    private fun tidied(held: JsonObject): String? {
        val restored = settings.withoutDoor(held, records.get().replaced, selectors)
        if (restored != held && !file.replace(held, restored)) return null
        spent()
        return if (restored == held) "" else ". What an older splice wrote into ${file.path} for Desktop was taken out"
    }

    /** The record of what an older `on` replaced is spent once the file was looked at: it is put back, or the file
     *  holds none of that door. Kept past that, it would take a selector the person picks later for the old door's. */
    private fun spent() {
        if (records.get().replaced != null) records.update { it.copy(replaced = null) }
    }
}

// why: Claude Code writes its settings file at a pick or a setting, seconds apart at the quickest, so a second look
// finds it still; the third is for a write that lands between the first two.
private const val LOOKS = 3
