// NEW: Oct 10, 2026 — where a session changed model, kept so the Sessions page can draw the joint.
//
// A move (TranscriptModelRewrite) rewrites the model of every assistant row it moves to the new command's model, so the
// transcript alone no longer says where the session changed. Each move that gives rows a model is recorded here: the
// session, the message it moved at (the first moved assistant message that carries an id, which survives the rewrite
// because the rewrite changes a row's model and thinking, never its message id), the model rows moved to, the command
// (the head's key) that moved them, and when. One owner-only file per session holds its moves, oldest first.
package splice.client.resume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import splice.client.Keys
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.sessions.transcript.ModelMove
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val sessionIdShape = Regex("[A-Za-z0-9_-]{1,128}")
private const val MOVED_MESSAGE = "message_id"
private const val HEAD_COMMAND = "command"
private const val MOVED_AT = "moved_at"

public class ModelMoves(private val dir: Path) {
    /** Adds [move] to [sessionId]'s record. A session id that is no id records nothing. */
    public fun record(sessionId: String, move: ModelMove): Unit = synchronized(this) {
        if (!sessionIdShape.matches(sessionId)) return
        val all = of(sessionId) + move
        val body = JsonArray(
            all.map { kept ->
                buildJsonObject {
                    put(MOVED_MESSAGE, JsonPrimitive(kept.messageId))
                    put(Keys.MODEL, JsonPrimitive(kept.model))
                    kept.command?.let { put(HEAD_COMMAND, JsonPrimitive(it)) }
                    put(MOVED_AT, JsonPrimitive(kept.movedAt))
                }
            },
        )
        SecureFile.writeAtomic0600(dir.resolve("$sessionId.json"), body.toString())
    }

    /** The moves recorded for [sessionId], oldest first; none for a session that was never moved. */
    public fun of(sessionId: String): List<ModelMove> {
        if (!sessionIdShape.matches(sessionId)) return emptyList()
        val text = try {
            Files.readString(dir.resolve("$sessionId.json"))
        } catch (_: NoSuchFileException) {
            return emptyList()
        } catch (failure: IOException) {
            throw IOException("cannot read the model moves of $sessionId: ${SafeFailureText.render(failure)}", failure)
        }
        return Json.parseToJsonElement(text).jsonArray.map { moveOf(it.jsonObject) }
    }

    private fun moveOf(row: JsonObject): ModelMove = ModelMove(
        messageId = row.getValue(MOVED_MESSAGE).jsonPrimitive.content,
        model = row.getValue(Keys.MODEL).jsonPrimitive.content,
        command = row[HEAD_COMMAND]?.jsonPrimitive?.content,
        movedAt = row[MOVED_AT]?.jsonPrimitive?.longOrNull ?: row.getValue(MOVED_AT).jsonPrimitive.long,
    )
}
