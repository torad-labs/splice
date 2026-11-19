// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4, 6 and 8). What `splice desktop on`, `off` and
// `rotate-key` do to the door's record, and what each tells the person to set in Claude Desktop.
//
// splice writes nothing into `~/.claude/settings.json`, which every terminal session hot-reloads: the Code tab is
// pointed at the door by variables the person sets once in Desktop's own environment store (DesktopEnvironment), which
// no terminal session reads. `on` refuses, changing nothing, when no head is in the door, when a `--model` is named,
// or when the settings file would send the Code tab past the door; it names what it found. It opens the door, and
// says what to set and its values. A Code tab session starts on the model the terminal's `claude` starts on
// (ClaudeLane): Desktop keeps no model of its own, so `on` takes none, and says where `claude`'s is named. `off`
// closes the door, so it refuses every turn, and says which variables to remove to put the Code tab back on the
// person's own Claude login. What an older splice's `on` wrote into the settings file is taken out at each start, and
// by `on` and `off`.
package splice.app.desktop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import splice.client.desktop.DESKTOP_ENV_PLACE
import splice.client.desktop.DesktopEnvironment
import splice.client.desktop.DesktopSettings
import splice.client.desktop.DoorSelectors
import splice.client.desktop.SettingsRead
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus

/** What one of the switch's acts ended in. */
internal sealed class DesktopAct {
    /** Done: [said] is what changed, in words, [default] which model a session starts on and why it is that one, and
     *  [env] what to set in Desktop's environment store, with its values. */
    data class Done(
        val said: String,
        val default: String? = null,
        val env: List<Pair<String, String>>? = null,
    ) : DesktopAct()

    /** Refused with nothing changed: [status], and [reason], which [why] says with that. */
    data class Refused(val status: Int, val reason: String) : DesktopAct() {
        val why: String get() = "$reason; nothing was changed"
    }
}

internal class DesktopSwitch(
    private val records: DesktopRecords,
    private val key: DesktopKey,
    private val file: DesktopSettingsFile,
    /** The terminal's lane on the same door: its address, its lineup, and the model and window a session starts on. */
    private val lane: ClaudeLane,
    private val selectors: DoorSelectors,
    private val log: LogSink,
) {
    private val settings = DesktopSettings()
    private val environment = DesktopEnvironment()
    private val lock = Mutex()
    private val door = lane.door

    suspend fun on(model: String?): DesktopAct = lock.withLock {
        val lineup = lane.lineup()
        refusal(lineup, model)?.let { return@withLock it }
        val (chosen, why) = lane.starting(lineup)
        if (chosen == null) return@withLock DesktopAct.Refused(HttpStatus.CONFLICT, NO_MODEL)
        // The window Desktop is told is set once and kept, so what the person set stays right: the head scales each
        // session's counts to it, whichever model the door runs. At the first `on` it is the terminal's.
        val window = records.get().window ?: lane.window(lineup)
        val tidied = tidied()
        records.update { it.copy(on = true, window = window, replaced = null) }
        val said = "Desktop is on: the door answers at $door. Your ${file.path} is not changed, so terminal " +
            "sessions stay as they are$tidied"
        DesktopAct.Done(said, "$chosen, the model `claude` starts on ($why)", environment.of(door, key.get(), window))
    }

    /** Why `on` must change nothing: a `--model` named, no head in the door, or a settings file it can't read or that
     *  would send the Code tab past the door. Null when it may go on. */
    private fun refusal(lineup: DesktopLineup, model: String?): DesktopAct? {
        val read = file.read()
        val found = when {
            model != null -> HttpStatus.BAD_REQUEST to NO_OWN_MODEL
            lineup.models.isEmpty() -> HttpStatus.CONFLICT to "no head is in the door (${leftOut(lineup)})"
            read is SettingsRead.Unreadable -> HttpStatus.CONFLICT to read.why
            read is SettingsRead.Held -> settings.refusal(read.file)?.let { HttpStatus.CONFLICT to "${file.path}: $it" }
            else -> null
        }
        return found?.let { (status, why) -> DesktopAct.Refused(status, why) }
    }

    private fun leftOut(lineup: DesktopLineup): String =
        lineup.leftOut.joinToString("; ") { "${it.command}: ${it.reason}" }.ifEmpty { "splice runs no head" }

    suspend fun off(): DesktopAct = lock.withLock {
        val was = records.get().on
        val tidied = tidied()
        records.update { it.copy(on = false, replaced = null) }
        val said = if (was) "Desktop is off: the door refuses the Code tab's turns" else "Desktop is already off"
        DesktopAct.Done("$said. $LEAVE ${environment.names.joinToString()} in $DESKTOP_ENV_PLACE$tidied")
    }

    /** A new desktop key; the old one is refused from now on, so the person sets the new one in Desktop. */
    suspend fun rotate(): DesktopAct = lock.withLock {
        val fresh = key.replace()
        val record = records.get()
        val window = record.window
        if (!record.on || window == null) {
            return@withLock DesktopAct.Done(
                "the desktop key was replaced and the old one is refused. Desktop is off; `splice desktop on` says " +
                    "what to set in $DESKTOP_ENV_PLACE",
            )
        }
        val env = environment.of(door, fresh, window)
        DesktopAct.Done("the desktop key was replaced and the old one is refused", env = env)
    }

    /** At each start, before the routes serve: what an older splice's `on` wrote into the settings file taken out,
     *  said in the log. A file splice can't read or write is said in the log too, and the door still opens. */
    fun tidy() {
        Cancellables.runCatchingCancellable { tidied() }
            .onSuccess { if (it.isNotEmpty()) log("[desktop] ${it.removePrefix(". ")}\n") }
            .onFailure { log("[desktop] ${file.path} could not be tidied: ${SafeFailureText.render(it)}\n") }
    }

    /** What an older splice's `on` wrote into the settings file taken out, and a sentence saying so, empty when there
     *  was none. A file splice can't read is said in the sentence and left as it is, and so is one Claude Code wrote
     *  each time splice looked. */
    private fun tidied(): String = (1..LOOKS).firstNotNullOfOrNull { looked() }
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

// What `on` answers when the last head left the door between two reads.
private const val NO_MODEL = "no model is in the door"

// What `on --model` answers: Desktop has no model of its own to name.
private const val NO_OWN_MODEL = "`splice desktop on` takes no --model: a Code tab session starts on the model " +
    "`claude` starts on, which `splice claude` says and `splice claude door --model <selector>` names"

// why: Claude Code writes its settings file at a pick or a setting, seconds apart at the quickest, so a second look
// finds it still; the third is for a write that lands between the first two.
private const val LOOKS = 3

// How `off` tells the person to leave the door, before the variables' names.
private const val LEAVE = "To put the Code tab back on your own Claude login, remove"
