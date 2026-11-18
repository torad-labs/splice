// NEW: door-default (docs/specs/door-default.md, decisions 2, 4 and 5). What a launch of `claude` carries while it is
// on the door: the door, the terminal's key, the window, the model a session starts on and the picker's rows (the
// models of signed-in accounts), read from the lane and the door's lineup at each launch, so a sign-in reaches the
// next session with no restart; and splice's /login (DoorLogin), its file laid in the person's Claude folder where it
// is missing and its hook in the overlay. Null on the head lane, which launches `claude` on its head as before, with
// the same rows added after Claude Code's own models (decision 9, headPicker).
package splice.app.desktop

import kotlinx.serialization.json.JsonObject
import splice.client.desktop.DoorPicker
import splice.client.desktop.DoorPickerRow
import splice.client.desktop.DoorSession
import splice.client.login.DoorLogin
import splice.core.usage.QuotaFullWindow
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.launch.recipe.DoorLaunchSpec
import splice.sessions.registry.LaunchMarker

internal class ClaudeDoorLaunch(
    private val lane: ClaudeLane,
    private val login: DoorLogin,
    private val log: LogSink,
) {
    suspend fun launch(): DoorLaunchSpec? {
        if (!lane.onDoor()) return null
        val lineup = lane.lineup()
        val rows = rowsOf(lineup)
        val session = DoorSession(lane.door, lane.key.get(), lane.window(lineup), lane.starting(lineup).first, rows)
        return DoorLaunchSpec(
            session.env(),
            session.unset(),
            session.overlay(LaunchMarker.here, LaunchMarker.SPLICE_LAUNCH_KEY, hooks()),
        )
    }

    /** Decision 9: the rows a launch of `claude` on its head adds after Claude Code's own models, the same rows the door
     *  lists, read at each launch so a sign-in reaches the next session with no restart; null on the door and with no
     *  account signed in. */
    suspend fun headPicker(): DoorPicker? {
        if (lane.onDoor()) return null
        return rowsOf(lane.lineup()).takeIf { it.isNotEmpty() }?.let(::DoorPicker)
    }

    /** The picker's rows: the models of the signed-in accounts, in splice.toml's order of heads. A model whose account
     *  reads a plan window fully used stays listed, with the window marked beside its name (Marcos, Nov 17, 2026): the
     *  head still serves it, and the person sees before picking it that the account is spent. */
    private fun rowsOf(lineup: DesktopLineup): List<DoorPickerRow> =
        lineup.signedIn.map { DoorPickerRow(it.model, labelOf(it), it.command) }

    /** [model]'s label, with its account's full window beside it as `splice status` writes it (`7d 100%`). */
    private fun labelOf(model: DoorModel): String {
        val full = model.full ?: return model.label
        val window = if (full.window == QuotaFullWindow.FIVE_HOUR) FIVE_HOUR_MARK else SEVEN_DAY_MARK
        return "${model.label} · $window 100%"
    }

    /** splice's /login laid, and the hooks that answer it; none when the disk refuses, said in the log: the session
     *  starts without splice's /login rather than not at all. A /login of the person's in the way is left as it is,
     *  with no hook to catch what they typed, and `splice claude` says so. */
    private fun hooks(): Map<String, List<JsonObject>> = Cancellables.runCatchingCancellable {
        if (login.lay() == null) login.hooks() else emptyMap()
    }.getOrElse { failure ->
        log("[claude] splice's /login was not laid, so /login is not splice's: ${SafeFailureText.render(failure)}\n")
        emptyMap()
    }
}

// The windows a picker row names as fully used, as `splice status` names them on its accounts lines.
private const val FIVE_HOUR_MARK = "5h"
private const val SEVEN_DAY_MARK = "7d"
