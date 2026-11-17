// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4, 6 and 8). What `splice desktop on`, `off` and
// `rotate-key` do to the door's record, and what each tells the person to set in Claude Desktop.
//
// splice writes nothing into `~/.claude/settings.json`, which every terminal session hot-reloads: the Code tab is
// pointed at the door by variables the person sets once in Desktop's own environment store (DesktopEnvironment), which
// no terminal session reads. `on` refuses, changing nothing, when no head is in the door, when `--model` is not in it,
// or when the settings file would send the Code tab past the door; it names what it found. It opens the door, and
// says what to set and its values. `off` closes the door, so it refuses every turn, and says which variables to remove
// to put the Code tab back on the person's own Claude login. What an older splice's `on` wrote into the settings file
// is taken out at each start, and by `on` and `off`.
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
    private val heads: DesktopHeads,
    private val records: DesktopRecords,
    private val key: DesktopKey,
    private val file: DesktopSettingsFile,
    /** The door's address, which Desktop's environment store gives the Code tab's Claude Code. */
    val door: String,
    private val selectors: DoorSelectors,
    private val log: LogSink,
) {
    private val settings = DesktopSettings()
    private val environment = DesktopEnvironment()
    private val lock = Mutex()
    private val starting = StartingModel({ records.get().defaultModel }, picker = false)

    suspend fun on(model: String?): DesktopAct = lock.withLock {
        val lineup = heads.lineup()
        refusal(lineup, model)?.let { return@withLock it }
        val (chosen, why) = starting.of(lineup, model)
        val found = chosen?.let(lineup::find) ?: return@withLock DesktopAct.Refused(HttpStatus.CONFLICT, NO_MODEL)
        val (head, picked) = found
        // The window Desktop is told is set once and kept, so what the person set stays right: the head scales each
        // session's counts to it, whichever model the door runs.
        val window = records.get().window ?: head.catalog.contextWindowFor(picked.upstream)
        val tidied = tidied()
        records.update { it.copy(on = true, defaultModel = chosen, window = window, replaced = null) }
        val said = "Desktop is on: the door answers at $door. Your ${file.path} is not changed, so terminal " +
            "sessions stay as they are$tidied"
        DesktopAct.Done(said, "$chosen ($why)", environment.of(door, key.get(), window))
    }

    /** Why `on` must change nothing: no head in the door, a `--model` not in it, or a settings file it can't read or
     *  that would send the Code tab past the door. Null when it may go on. */
    private fun refusal(lineup: DesktopLineup, model: String?): DesktopAct? {
        val read = file.read()
        val notServed = starting.refusal(lineup, model)
        val found = when {
            lineup.models.isEmpty() -> HttpStatus.CONFLICT to "no head is in the door (${leftOut(lineup)})"
            notServed != null -> HttpStatus.BAD_REQUEST to notServed
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
     *  was none. A file splice can't read is said in the sentence and left as it is. */
    private fun tidied(): String = when (val read = file.read()) {
        is SettingsRead.Unreadable -> ". The old door's values could not be looked for: ${read.why}"
        SettingsRead.Absent -> ""
        is SettingsRead.Held -> tidied(read.file)
    }

    /** [held], the settings file, with what an older splice's `on` wrote taken out, and a sentence saying so. */
    private fun tidied(held: JsonObject): String {
        val restored = settings.withoutDoor(held, records.get().replaced, selectors)
        if (restored == held) return ""
        if (restored == null) file.remove() else file.restore(restored)
        if (records.get().replaced != null) records.update { it.copy(replaced = null) }
        return ". What an older splice wrote into ${file.path} for Desktop was taken out"
    }

    /** The model a Code tab session starts on in [lineup]: the one chosen the last time, or the pinned model of the
     *  first head in the door; null when no head is in the door. */
    fun defaultOf(lineup: DesktopLineup): String? = starting.of(lineup).first
}

// What `on` answers when the default it chose has left the door between two reads.
private const val NO_MODEL = "no model is in the door"

// How `off` tells the person to leave the door, before the variables' names.
private const val LEAVE = "To put the Code tab back on your own Claude login, remove"
