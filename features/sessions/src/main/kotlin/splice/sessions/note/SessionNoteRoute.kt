// NEW: V4-444 — POST /api/sessions/{id}/message: a note from the console to one live session (the session page's "Send a
// note to this session"). It is PEER-NOTE delivery and claims nothing more (V4-444 audit, splice-builder2, Sep 29 9:36 PM
// CT): the session receives it as a message from another session, never as the operator typing, and it cannot approve,
// configure or run a command for it. The answer is 202 "submitted": writing the frame is transport, and the client gives no
// receipt for an accepted message, so delivery is always unknown.
//
// The target is resolved HERE from the registry, never from the client: one running session, alone on its socket. Running
// is every registration that is not GONE: the registry's STALE is a pid still alive that has not refreshed its file
// (SessionRegistry.kt), and its socket is that same process's. Whatever the request names besides the text is ignored.
package splice.sessions.note

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.http.JsonReply
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource

// why: a note is a few sentences typed into a box; anything past this is a paste the session's inbox should not be handed.
internal const val MAX_NOTE_CHARS = 8_000

private const val BAD_BODY = "body must be {\"text\": \"<the note>\"}"
private const val NO_SESSION = "no session has that id"
private const val NOT_RUNNING = "the session is not running"
private const val SEVERAL_WITH_ID = "more than one running session has that id"
private const val NO_SOCKET = "the session has no messaging socket"
private const val SHARED_SOCKET = "more than one running session answers on that socket"

/** What sending one note came to. Only [Submitted] put bytes on the socket. */
public sealed class NoteOutcome {
    public data class Submitted(val messageId: String) : NoteOutcome()

    /** Nothing was sent: the target or its version is not one this route may write to. */
    public data class Refused(val status: HttpStatusCode, val reason: String) : NoteOutcome()

    /** The socket could not be reached or written. Not retried: a half-written note may have arrived. */
    public data class Failed(val reason: String) : NoteOutcome()
}

public fun interface SessionNoteSender {
    public suspend fun send(target: SessionRecord, text: String): NoteOutcome
}

/** One check's answer: the value to carry on with, or the reply that ends the request. */
private sealed class Step<out T> {
    data class Go<T>(val value: T) : Step<T>()

    data class Stop(val reply: JsonReply) : Step<Nothing>()
}

public class SessionNoteRoute(
    private val registry: SessionSource,
    private val sender: SessionNoteSender?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun stop(status: HttpStatusCode, reason: String): Step.Stop =
        Step.Stop(JsonReply(status, buildJsonObject { put("error", reason) }.toString()))

    public suspend fun post(sessionId: String, body: String): JsonReply =
        when (val text = note(body)) {
            is Step.Stop -> text.reply
            is Step.Go -> when (val target = target(sessionId)) {
                is Step.Stop -> target.reply
                is Step.Go -> submit(target.value, text.value)
            }
        }

    private suspend fun submit(target: SessionRecord, text: String): JsonReply {
        val sender = sender ?: return stop(HttpStatusCode.NotImplemented, "this splice cannot send notes").reply
        return when (val outcome = sender.send(target, text)) {
            is NoteOutcome.Submitted -> JsonReply(
                HttpStatusCode.Accepted,
                buildJsonObject {
                    put("submitted", true)
                    put("message_id", outcome.messageId)
                    // The client gives no receipt for an accepted message, so nothing here can say it was read.
                    put("delivery", "unknown")
                }.toString(),
            )
            is NoteOutcome.Refused -> stop(outcome.status, outcome.reason).reply
            is NoteOutcome.Failed -> stop(HttpStatusCode.BadGateway, outcome.reason).reply
        }
    }

    private fun note(body: String): Step<String> {
        val text = textOf(body)
        return when {
            text == null -> stop(HttpStatusCode.BadRequest, BAD_BODY)
            text.isBlank() -> stop(HttpStatusCode.BadRequest, "the note is empty")
            text.length > MAX_NOTE_CHARS ->
                stop(HttpStatusCode.BadRequest, "a note is at most $MAX_NOTE_CHARS characters")
            else -> Step.Go(text)
        }
    }

    /** The one running session with this id that owns its socket alone. */
    private fun target(sessionId: String): Step<SessionRecord> {
        val all = registry.read()
        val running = all.filter { it.availability != SessionAvailability.GONE }
        val mine = running.filter { it.sessionId == sessionId }
        val record = mine.singleOrNull()
        val socket = record?.messagingSocketPath
        val sharing = running.count { it.messagingSocketPath == socket }
        return when {
            all.none { it.sessionId == sessionId } -> stop(HttpStatusCode.NotFound, NO_SESSION)
            mine.isEmpty() -> stop(HttpStatusCode.Conflict, NOT_RUNNING)
            record == null -> stop(HttpStatusCode.Conflict, SEVERAL_WITH_ID)
            socket == null -> stop(HttpStatusCode.Conflict, NO_SOCKET)
            sharing > 1 -> stop(HttpStatusCode.Conflict, SHARED_SOCKET)
            else -> Step.Go(record)
        }
    }

    // ast-grep-ignore: kt-no-silent-result-collapse -- a body that is not a JSON object with a string `text` is a 400, answered by the caller
    private fun textOf(body: String): String? = Cancellables.runCatchingCancellable {
        json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.takeIf { it.isString }?.content
    }.getOrNull()
}
