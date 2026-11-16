// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4 and 6). What `splice desktop on`, `off` and `rotate-key`
// do to the settings file and the door's record, and the lineup following splice while Desktop is on.
//
// `on` refuses, changing nothing, when no head is in the door, when `--model` is not in it, or when the file points
// Claude Code at a provider that is not splice's door; it names what it found. Otherwise the record of what it replaces
// is written before the file, so `off` can always put it back. While Desktop is on, the picker in the file follows the
// heads splice serves (at each start, and whenever the door's state is read), and the door, the key and the window stay
// as the file held them.
package splice.app.desktop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.client.desktop.DesktopRow
import splice.client.desktop.DesktopSettings
import splice.client.desktop.DesktopWrite
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus

/** What one of the switch's acts ended in. */
internal sealed class DesktopAct {
    /** Done: [said] is what changed, in words, and [default] which model a session starts on and why it is that one. */
    data class Done(val said: String, val default: String? = null) : DesktopAct()

    /** Refused with nothing changed: [status] and [why]. */
    data class Refused(val status: Int, val why: String) : DesktopAct()
}

internal class DesktopSwitch(
    private val heads: DesktopHeads,
    private val records: DesktopRecords,
    private val key: DesktopKey,
    private val file: DesktopSettingsFile,
    /** The door's address, which the file gives the Code tab's Claude Code. */
    val door: String,
    private val log: LogSink,
) {
    private val settings = DesktopSettings()
    private val lock = Mutex()

    suspend fun on(model: String?): DesktopAct = lock.withLock {
        val lineup = heads.lineup()
        val read = file.read()
        refusal(lineup, model, read)?.let { return@withLock it }
        val held = (read as? SettingsRead.Held)?.file
        val (chosen, why) = defaultOf(lineup, model)
        val (head, picked) = lineup.find(chosen) ?: return@withLock refused(HttpStatus.CONFLICT, NO_MODEL)
        val window = head.catalog.contextWindowFor(picked.upstream)
        val write = DesktopWrite(door, key.get(), window, chosen, rows(lineup))
        val written = settings.on(held, write, records.get().replaced)
        records.update { it.copy(on = true, defaultModel = chosen, window = window, replaced = written.replaced) }
        file.write(written.file)
        DesktopAct.Done("Desktop is on: the Code tab's next session runs on splice at $door", "$chosen ($why)")
    }

    /** Why `on` must change nothing: no head in the door, a `--model` not in it, or a file it can't read or that points
     *  Claude Code elsewhere. Null when it may go on. */
    private fun refusal(lineup: DesktopLineup, model: String?, read: SettingsRead): DesktopAct? {
        val found = when {
            lineup.models.isEmpty() -> HttpStatus.CONFLICT to "no head is in the door (${leftOut(lineup)})"
            model != null && lineup.find(model) == null ->
                HttpStatus.BAD_REQUEST to
                    "--model $model is not in the door, which serves ${lineup.models.joinToString { it.model }}"
            read is SettingsRead.Unreadable -> HttpStatus.CONFLICT to read.why
            read is SettingsRead.Held ->
                settings.refusal(read.file, door)?.let { HttpStatus.CONFLICT to "${file.path}: $it" }
            else -> null
        }
        return found?.let { (status, why) -> refused(status, why) }
    }

    private fun leftOut(lineup: DesktopLineup): String =
        lineup.leftOut.joinToString("; ") { "${it.command}: ${it.reason}" }.ifEmpty { "splice runs no head" }

    suspend fun off(): DesktopAct = lock.withLock {
        val record = records.get()
        val held = when (val read = file.read()) {
            is SettingsRead.Unreadable -> return@withLock refused(HttpStatus.CONFLICT, read.why)
            SettingsRead.Absent -> null
            is SettingsRead.Held -> read.file
        }
        val ours = held?.takeIf { settings.pointsAt(it, door) }
        if (!record.on && ours == null) return@withLock DesktopAct.Done("Desktop is already off; nothing was changed")
        if (ours != null) {
            val restored = settings.off(ours, record.replaced)
            if (restored == null) file.remove() else file.write(restored)
        }
        records.update { it.copy(on = false, replaced = null) }
        DesktopAct.Done("Desktop is off: the Code tab's next session runs on your own Claude login")
    }

    /** A new desktop key, written into the file when Desktop is on; the old one is refused from now on. */
    suspend fun rotate(): DesktopAct = lock.withLock {
        val fresh = key.replace()
        val held = ours()?.takeIf { records.get().on }
            ?: return@withLock DesktopAct.Done(
                "the desktop key was replaced and the old one is refused. Desktop is off, so the settings file was " +
                    "not changed; `splice desktop on` writes the new key",
            )
        file.write(settings.rekeyed(held, fresh))
        DesktopAct.Done("the desktop key was replaced in ${file.path}, and the old one is refused from now on")
    }

    /** While Desktop is on, the file's picker made to list [lineup], and nothing else changed. A file splice can't
     *  write is said in the log; the door still refuses a selector that left the lineup. */
    suspend fun follow(lineup: DesktopLineup) {
        if (!records.get().on) return
        lock.withLock {
            val held = ours() ?: return@withLock
            val next = settings.follow(held, rows(lineup))
            if (next == held) return@withLock
            Cancellables.runCatchingCancellable { file.write(next) }.onFailure { failure ->
                val why = SafeFailureText.render(failure)
                log("[desktop] the picker in ${file.path} could not follow splice: $why\n")
            }
        }
    }

    /** The model a session starts on, and which rule chose it: [named], or the one chosen the last time, or the
     *  pinned model of the first head in the door. Empty when no head is in the door. */
    fun defaultOf(lineup: DesktopLineup, named: String? = null): Pair<String, String> {
        if (named != null) return named to "the --model you named"
        val last = records.get().defaultModel?.takeIf { lineup.find(it) != null }
        return if (last != null) last to "the one chosen the last time" else firstPinned(lineup)
    }

    /** The pinned model of the first head in the door, or its first model when its pin is not in the door. */
    private fun firstPinned(lineup: DesktopLineup): Pair<String, String> {
        val first = lineup.heads.firstOrNull() ?: return "" to "no head is in the door"
        val pinned = first.catalog.wrap(first.pinned).takeIf { lineup.find(it) != null }
            ?: lineup.models.firstOrNull { it.head == first.key }?.model.orEmpty()
        return pinned to "the pinned model of ${first.relay.command}, the first head in the door"
    }

    /** The settings file while it holds this door, as `on` left it; null otherwise. */
    private fun ours() = (file.read() as? SettingsRead.Held)?.file?.takeIf { settings.pointsAt(it, door) }

    private fun refused(status: Int, why: String): DesktopAct = DesktopAct.Refused(status, "$why; nothing was changed")

    private fun rows(lineup: DesktopLineup): List<DesktopRow> =
        lineup.models.map { DesktopRow(it.model, it.label, "splice · ${it.command}") }
}

// What `on` answers when the default it chose has left the door between two reads.
private const val NO_MODEL = "no model is in the door"
