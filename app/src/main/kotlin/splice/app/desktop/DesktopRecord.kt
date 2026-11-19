// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4 and 7). The door's own record in splice's state folder:
// whether Desktop is on, the window Desktop is told, what an older splice's `on` replaced in the settings file until
// it is put back, and the last turn sent through the door, kept as one of splice's state records (StateRecords). The
// model a Code tab session starts on is not kept here: it is the one the terminal's `claude` starts on (ClaudeLane), so
// a record an older splice wrote a `default_model` into is read without it.
package splice.app.desktop

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink
import java.nio.file.Path

/** The last turn sent through the door: when, which session, the head, the model it asked for and the model it was
 *  sent to the head as. Recorded as it is sent, so it says nothing of how the turn ended. */
@Serializable
internal data class DesktopLastTurn(
    val at: String,
    @SerialName("session_id") val sessionId: String?,
    val head: String,
    @SerialName("model_asked") val modelAsked: String,
    val model: String,
)

@Serializable
internal data class DesktopRecord(
    val on: Boolean = false,
    /** The window Desktop is told, set at the first `on` and kept: every count the door reports is a share of it. */
    val window: Long? = null,
    /** What an older splice's `on` replaced in the settings file, until it is put back (withoutDoor). */
    val replaced: JsonObject? = null,
    @SerialName("last_turn") val lastTurn: DesktopLastTurn? = null,
)

/** Desktop's record, in splice's state folder. */
internal class DesktopRecords(file: Path, log: LogSink) :
    StateRecords<DesktopRecord>(file, DesktopRecord.serializer(), DesktopRecord(), "desktop", log)
