// NEW: door-default (docs/specs/door-default.md, decisions 1 and 3). The terminal lane's record in splice's state
// folder: the lane a person chose for `claude` with `splice claude door` or `head` (none until they choose), the model
// they named with `--model`, and the window the terminal's sessions declare, fixed at its first use. Kept as one of
// splice's state records (StateRecords), so a choice holds across restarts and upgrades.
package splice.app.desktop.lane

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import splice.app.desktop.record.StateRecords
import splice.core.util.LogSink
import java.nio.file.Path

@Serializable
internal data class ClaudeLaneRecord(
    /** [DOOR_LANE] or [HEAD_LANE] once the person has chosen; null until then. */
    val lane: String? = null,
    /** The selector named with `--model`, kept until another is named. */
    @SerialName("default_model") val defaultModel: String? = null,
    /** The window the terminal's sessions declare: every count the door reports to them is a share of it. */
    val window: Long? = null,
)

/** The terminal lane's record, in splice's state folder. */
internal class ClaudeLaneRecords(file: Path, log: LogSink) :
    StateRecords<ClaudeLaneRecord>(file, ClaudeLaneRecord.serializer(), ClaudeLaneRecord(), "claude", log) {

    /** The lane `claude` is on: the one chosen, or with no choice made, its head while [headHere] says there is one
     *  and the door otherwise. */
    fun lane(headHere: ClaudeHeadHere): String = get().lane ?: if (headHere()) HEAD_LANE else DOOR_LANE
}

/** The lane `claude` runs on through the door. */
internal const val DOOR_LANE = "door"

/** The lane `claude` runs on through its head, which carries the door's models (decision 9). */
internal const val HEAD_LANE = "head"
