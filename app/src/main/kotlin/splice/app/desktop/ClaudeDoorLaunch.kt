// NEW: door-default (docs/specs/door-default.md, decisions 2, 4 and 5). What a launch of `claude` carries while it is
// on the door: the door, the terminal's key, the window, the model a session starts on and the picker's rows (the
// models of signed-in accounts, and the models splice.toml declares for a head on a key), read from the lane and the door's lineup at each launch, so a sign-in reaches the
// next session with no restart; and splice's /login (DoorLogin), its file laid in the person's Claude folder where it
// is missing and its hook in the overlay. Null on the head lane, which launches `claude` on its head as before, with
// the same rows added after Claude Code's own models (decision 9, headPicker).
package splice.app.desktop

import kotlinx.serialization.json.JsonObject
import splice.client.desktop.DoorPicker
import splice.client.desktop.DoorPickerRow
import splice.client.desktop.DoorSession
import splice.client.login.DoorLogin
import splice.core.topology.DoorCommandConfig
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
        return spec(lineup, lane.starting(lineup).first, rowsOf(lineup))
    }

    /** custom-provider (cp-command): a launch of the door command [command], on the door whatever lane `claude` is on.
     *  A session starts on its head's pinned model, and its picker is that head's rows or, where it says `models =
     *  "door"`, the door's whole picker, from the same catalog. It declares the terminal's window, which every turn
     *  the terminal's key sends is scaled to. With its head out of the door it starts on no model, as `claude` does with
     *  no head in the door. */
    suspend fun launchCommand(command: DoorCommandConfig): DoorLaunchSpec {
        val lineup = lane.lineup()
        val head = lineup.heads.firstOrNull { it.key == command.head }
        val all = rowsOf(lineup)
        val rows = if (command.listsDoor) all else all.filter { lineup.find(it.model)?.first?.key == command.head }
        return spec(lineup, head?.let(lineup::pinnedOf)?.ifEmpty { null }, rows)
    }

    private fun spec(lineup: DesktopLineup, model: String?, rows: List<DoorPickerRow>): DoorLaunchSpec {
        val session = DoorSession(lane.door, lane.key.get(), lane.window(lineup), model, rows)
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

    /** The picker's rows: the model catalog's, as the door serves them now (DoorCatalog.picker). */
    fun rowsOf(lineup: DesktopLineup): List<DoorPickerRow> = DoorCatalog(lineup).picker()

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
