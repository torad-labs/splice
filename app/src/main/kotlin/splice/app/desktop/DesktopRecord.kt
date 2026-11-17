// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4 and 7). The door's own record in splice's state folder:
// whether Desktop is on, the default model, the window Desktop is told, what an older splice's `on` replaced in the
// settings file until it is put back, and the last turn sent through the door. Kept here and never beside the settings
// file, which is the person's own. Written whole and atomically before a change takes effect.
package splice.app.desktop

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
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
    /** The selector a session starts on: `--model`, or the one chosen the last time. */
    @SerialName("default_model") val defaultModel: String? = null,
    /** The window Desktop is told, set at the first `on` and kept: every count the door reports is a share of it. */
    val window: Long? = null,
    /** What an older splice's `on` replaced in the settings file, until it is put back (withoutDoor). */
    val replaced: JsonObject? = null,
    @SerialName("last_turn") val lastTurn: DesktopLastTurn? = null,
)

/** The record, read once and kept in memory; a change reaches the disk before it is kept. A record splice can't read
 *  starts empty, said in the log. */
internal class DesktopRecords(private val file: Path, private val log: LogSink) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Volatile
    private var current: DesktopRecord = read()

    fun get(): DesktopRecord = current

    /** [change] made to the record, written, then kept. Throws the disk's [IOException] with nothing changed. */
    @Synchronized
    fun update(change: DesktopChange): DesktopRecord {
        val next = change(current)
        if (next != current) SecureFile.writeAtomic0600(file, json.encodeToString(DesktopRecord.serializer(), next))
        current = next
        return next
    }

    private fun read(): DesktopRecord = try {
        json.decodeFromString(DesktopRecord.serializer(), Files.readString(file))
    } catch (_: NoSuchFileException) {
        DesktopRecord()
    } catch (e: IOException) {
        unread(e)
    } catch (e: SerializationException) {
        unread(e)
    }

    private fun unread(e: Exception): DesktopRecord {
        val why = SafeFailureText.render(e)
        log("[desktop] ${file.fileName} could not be read, so the door starts with no record: $why\n")
        return DesktopRecord()
    }
}

/** One change to the door's record. */
internal fun interface DesktopChange {
    operator fun invoke(record: DesktopRecord): DesktopRecord
}
