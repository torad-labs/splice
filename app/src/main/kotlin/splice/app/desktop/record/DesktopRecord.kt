// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4 and 7). The door's own record in splice's state folder:
// whether Desktop is on, the default model, the window Desktop is told, what an older splice's `on` replaced in the
// settings file until it is put back, the last turn sent through the door, and (desktop-full) splice's configuration in
// Claude Desktop's library, which `on` puts in and `off` takes out, and what the door has seen of Desktop since; kept as
// one of splice's state records (StateRecords).
package splice.app.desktop.record

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink
import java.nio.file.Path
import java.time.Instant

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
    /** The selector a session starts on: `--model`, or the one chosen the last time. */
    @SerialName("default_model") val defaultModel: String? = null,
    /** What an older splice's `on` replaced in the settings file, until it is put back (withoutDoor). */
    val replaced: JsonObject? = null,
    @SerialName("last_turn") val lastTurn: DesktopLastTurn? = null,
    /** desktop-full: the id of splice's configuration in Claude Desktop's library, made at the first `on` and kept for
     *  every later one. */
    @SerialName("app_id") val appId: String? = null,
    /** What putting splice's configuration in replaced, while it is in the library; null while it is not. A record
     *  that says on with none is the door an older splice opened through Desktop's environment store. */
    @SerialName("app_library") val appLibrary: DesktopAppRecord? = null,
    /** When splice last wrote or removed its configuration in Desktop's library: Desktop reads it only at launch. */
    @SerialName("config_written_at") val configWrittenAt: String? = null,
    /** The last time a request of Desktop's app mode reached the door: a turn, a count or a discovery. */
    @SerialName("app_seen_at") val appSeenAt: String? = null,
    /** The rows the door answered Desktop's last app-mode discovery with, kept across daemon restarts. */
    @SerialName("picker_read") val pickerRead: DesktopPickerRead? = null,
) {
    /** Whether an app-mode request of Desktop's reached the door after splice last changed its configuration: Desktop
     *  reads its configuration only at launch, so it has been reopened since. */
    fun seenSinceWritten(): Boolean {
        val seen = appSeenAt ?: return false
        val written = configWrittenAt ?: return true
        return !Instant.parse(seen).isBefore(Instant.parse(written))
    }
}

/** The picker Desktop last read: when, and each row's id and label, in order. */
@Serializable
internal data class DesktopPickerRead(val at: String, val rows: List<DesktopPickerRow>)

/** One picker row as Desktop read it. */
@Serializable
internal data class DesktopPickerRow(val id: String, val label: String)

/** What putting splice's configuration in Desktop's library replaced (DesktopAppLibrary): the configuration it applied
 *  before, `_meta.json` as it was (null when there was none), and whether splice made the library folder. */
@Serializable
internal data class DesktopAppRecord(
    @SerialName("replaced_applied") val replacedApplied: String? = null,
    @SerialName("meta_before") val metaBefore: String? = null,
    @SerialName("made_library") val madeLibrary: Boolean = false,
)

/** Desktop's record, in splice's state folder. */
internal class DesktopRecords(file: Path, log: LogSink) :
    StateRecords<DesktopRecord>(file, DesktopRecord.serializer(), DesktopRecord(), "desktop", log)
