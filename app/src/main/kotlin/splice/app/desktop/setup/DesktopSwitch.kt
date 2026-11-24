// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4, 6 and 8); desktop-full (docs/specs/desktop-full.md,
// df-app-on). What `splice desktop on`, `off` and `rotate-key` do.
//
// `on` sets Claude Desktop up itself, with nothing to paste: it puts splice's configuration in Desktop's 3P library
// (DesktopAppMode), pointing all of Desktop (Chat, Cowork and the Code tab) at the door as its gateway, with the
// desktop key from a credential helper Desktop runs, and opens the door. `--model` is Desktop's own default, the model
// it lists first and opens a new session on; the terminal's `claude` keeps its own. `on` refuses, changing nothing,
// when no provider is in the door, when `--model` is not in it, or when Desktop's library can't take splice's
// configuration; it names what it found. `off` takes out exactly what `on` put in and closes the door. Until Nov 21,
// 2026 `on` printed five variables for the person to paste into Desktop's environment store (the Code tab alone); a
// door opened that way is still answered until `on` or `off` replaces it.
//
// splice writes nothing into `~/.claude/settings.json`, which every terminal session hot-reloads, and changes nothing
// in the terminal's environment. What an older splice's `on` wrote into the settings file is taken out at each start,
// and by `on` and `off`.
package splice.app.desktop.setup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.DoorCatalog
import splice.app.desktop.catalog.StartingModel
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.record.DesktopRecords
import splice.client.desktop.DESKTOP_ENV_PLACE
import splice.client.desktop.DesktopEnvironment
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus

/** What one of the switch's acts ended in. */
internal sealed class DesktopAct {
    /** Done: [said] is what changed, in words, and [default] which model a session starts on and why it is that one. */
    data class Done(val said: String, val default: String? = null) : DesktopAct()

    /** Refused with nothing changed: [status], and [reason], which [why] says with that. */
    data class Refused(val status: Int, val reason: String) : DesktopAct() {
        val why: String get() = "$reason; nothing was changed"
    }
}

internal class DesktopSwitch(
    private val providers: DesktopProviders,
    private val records: DesktopRecords,
    private val key: DesktopKey,
    /** The door's address, which splice's configuration gives Desktop. */
    val door: String,
    /** splice's configuration in Desktop's library, and the mode Desktop is in. */
    val app: DesktopAppMode,
    /** What an older splice's `on` wrote into `~/.claude/settings.json`, taken out. */
    private val settingsTidy: DesktopSettingsTidy,
    private val log: LogSink,
) {
    private val lock = Mutex()
    private val starting = StartingModel({ records.get().defaultModel }, picker = false)

    suspend fun on(model: String?): DesktopAct = lock.withLock {
        val lineup = providers.lineup()
        val named = model?.let { picked(lineup, it) ?: it }
        refusal(lineup, named)?.let { return@withLock it }
        val (chosen, why) = starting.of(lineup, named)
        chosen?.let(lineup::find) ?: return@withLock DesktopAct.Refused(HttpStatus.CONFLICT, NO_MODEL)
        val tidied = settingsTidy.tidied()
        val replaced = app.putIn(door)
        records.update { it.copy(on = true, defaultModel = chosen, replaced = null) }
        app.publish(door, lineup, chosen)
        val said = "Claude Desktop is set up on splice: all of it (Chat, Cowork and the Code tab) runs on the door " +
            "at $door, with nothing to paste.$replaced $REOPEN$tidied"
        DesktopAct.Done(said, "$chosen ($why)")
    }

    /** The selector [model] stands for when it is the id Claude Desktop lists a model under (a Claude model's own id,
     *  or the name Claude's clients keep for another lab's): the person's own pick from Desktop's list, never dropped
     *  for not being spelled as a selector. Null when it is no id Desktop lists. */
    private fun picked(lineup: DesktopLineup, model: String): String? =
        lineup.find(model)?.second?.model
            ?: DoorCatalog(lineup).served.firstOrNull { it.desktopId == model }?.rows?.firstOrNull()?.model

    /** Why `on` must change nothing: no provider in the door, a `--model` not in it, or Desktop's library can't take
     *  splice's configuration. Null when it may go on. */
    private fun refusal(lineup: DesktopLineup, model: String?): DesktopAct? {
        val notServed = starting.refusal(lineup, model)
        val found = when {
            lineup.models.isEmpty() -> HttpStatus.CONFLICT to "no provider is in the door (${leftOut(lineup)})"
            notServed != null -> HttpStatus.BAD_REQUEST to notServed
            else -> app.refusal()?.let { HttpStatus.CONFLICT to it }
        }
        return found?.let { (status, why) -> DesktopAct.Refused(status, why) }
    }

    private fun leftOut(lineup: DesktopLineup): String =
        lineup.leftOut.joinToString("; ") { "${it.provider}: ${it.reason}" }.ifEmpty { "splice declares no provider" }

    /** The door closed, and what `on` put in Desktop's library taken out. A door an older splice opened through
     *  Desktop's environment store is closed too, and the variables to remove there are named: splice can't write that
     *  store. */
    suspend fun off(): DesktopAct = lock.withLock {
        val was = records.get().on
        val tidied = settingsTidy.tidied()
        val tookOut = app.takeOut()
        records.update { it.copy(on = false, replaced = null) }
        val said = when {
            tookOut -> "Desktop is off: splice's configuration was taken out of Claude Desktop. $OFF_APP"
            was -> "$OFF_OLD $LEAVE ${DesktopEnvironment().names.joinToString()} in $DESKTOP_ENV_PLACE"
            else -> "Desktop is already off"
        }
        DesktopAct.Done("$said$tidied")
    }

    /** A new desktop key; the old one is refused from now on. Desktop's credential helper reads the new one. */
    suspend fun rotate(): DesktopAct = lock.withLock {
        key.replace()
        val record = records.get()
        val said = when {
            record.appLibrary != null -> ROTATED_APP
            record.on -> "$ROTATED; run `splice desktop on` to set Claude Desktop up with it"
            else -> ROTATED
        }
        DesktopAct.Done(said)
    }

    /** At each start, before the routes serve: what an older splice's `on` wrote into the settings file taken out,
     *  and splice's configuration in Desktop's library kept current (its helper laid again for this splice, and the
     *  door's address in it corrected), each said in the log. A file splice can't read or write is said in the log
     *  too, and the door still opens. */
    fun tidy() {
        settingsTidy.atStart()
        Cancellables.runCatchingCancellable { app.refreshed(door) }
            .onSuccess { if (it) log("[desktop] $REWRITTEN\n") }
            .onFailure { log("[desktop] $NOT_CURRENT: ${SafeFailureText.render(it)}\n") }
    }

    /** The model a Code tab session starts on in [lineup]: the one chosen the last time, or the pinned model of the
     *  first provider in the door; null when no provider is in the door. */
    fun defaultOf(lineup: DesktopLineup): String? = starting.of(lineup).first
}

// What `on` answers when the default it chose has left the door between two reads.
private const val NO_MODEL = "no model is in the door"

// How `off` tells the person to leave the door, before the variables' names.
private const val LEAVE = "To put the Code tab back on your own Claude login, remove"

// Desktop reads its configuration only at launch.
private const val REOPEN = "Quit and reopen Claude Desktop for it to take effect"

// What `off` says once splice's configuration is out of Desktop's library.
private const val OFF_APP = "Quit and reopen Claude Desktop: it opens on your claude.ai login again"

// What `rotate-key` says.
private const val ROTATED = "the desktop key was replaced and the old one is refused"
private const val ROTATED_APP = "$ROTATED; Claude Desktop's credential helper gives it the new key within five minutes"

// What a start says when it corrected the door's address in splice's configuration.
private const val REWRITTEN = "splice's configuration in Claude Desktop now names this door's address; quit and " +
    "reopen Claude Desktop to use it"

// What a start says when splice's configuration in Claude Desktop could not be kept current, before why.
private const val NOT_CURRENT = "Claude Desktop's configuration could not be kept current"

// What `off` says of a door an older splice opened through Desktop's environment variables.
private const val OFF_OLD = "Desktop is off: the door refuses the Code tab's turns."
