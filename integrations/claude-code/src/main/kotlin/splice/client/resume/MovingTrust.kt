// NEW: Oct 11, 2026 — the folder a move in flight carries the operator's trust for, while it is in flight.
//
// WHY A RECORD AT ALL. Claude Code keeps a home-folder trust for the session only: its own session flag, never a
// line on disk (bundle fn vwe, `sessionFlags.homeTrustDialogAccepted`). So a session running in his home folder
// holds a trust he granted and nothing on disk says so. Continue on ends that client and starts another, which asks
// the safety check again, and the move stops there with his agent already ended. This is where the daemon says, for
// the length of one move, which folder it is moving a session out of: the launch reads it (FolderTrust carries it
// into the head's own config), and the move's end takes it away, so the grant never outlives the move. A daemon that
// died mid-move leaves a file; [sweep] at the next start is what clears it (Marlin, Oct 11).
//
// NO LOCK. Each member is one atomic file operation over its own session's file, and nothing is held in memory
// between them, so there is no state for a monitor to keep consistent: a write is SecureFile's atomic replace, a
// read is one read, and an end is one delete. Two moves of the same session cannot be in flight at once.
package splice.client.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries

private val movingSessionShape = Regex("[A-Za-z0-9_-]{1,128}")
private const val FOLDER = "folder"
private const val PRESSED_AT = "pressed_at"
private const val MOVING_SUFFIX = ".json"

/** The moves in flight, by session: one owner-only file each, in [dir]. */
public class MovingTrust(private val dir: Path) {
    /** [session] is moving out of [folder], which he had open and trusted. Kept until the move ends. */
    public fun granted(session: String, folder: Path, pressedAt: Long) {
        if (!movingSessionShape.matches(session)) return
        val body = buildJsonObject {
            put(FOLDER, JsonPrimitive(folder.toAbsolutePath().normalize().toString()))
            put(PRESSED_AT, JsonPrimitive(pressedAt))
        }
        SecureFile.writeAtomic0600(fileOf(session), body.toString())
    }

    /** The folder [session]'s move in flight carries, or null when no move of its is in flight. */
    public fun folderOf(session: String): Path? {
        if (!movingSessionShape.matches(session)) return null
        val row = read(fileOf(session)) ?: return null
        return (row[FOLDER] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)?.let(Path::of)
    }

    /** [session]'s move ended, however it ended: the record goes. */
    public fun done(session: String) {
        if (!movingSessionShape.matches(session)) return
        Files.deleteIfExists(fileOf(session))
    }

    /** Every record left behind is cleared: no move is in flight across a daemon start. Answers how many it cleared. */
    public fun sweep(): Int {
        val left = try {
            dir.listDirectoryEntries("*$MOVING_SUFFIX")
        } catch (_: NoSuchFileException) {
            return 0
        } catch (failure: IOException) {
            throw IOException("cannot read the moves in flight: ${SafeFailureText.render(failure)}", failure)
        }
        return left.count { Files.deleteIfExists(it) }
    }

    private fun fileOf(session: String): Path = dir.resolve("$session$MOVING_SUFFIX")

    private fun read(file: Path): JsonObject? {
        val text = try {
            Files.readString(file)
        } catch (_: NoSuchFileException) {
            return null
        } catch (failure: IOException) {
            throw IOException("cannot read the move in flight: ${SafeFailureText.render(failure)}", failure)
        }
        return Json.parseToJsonElement(text).jsonObject
    }
}
