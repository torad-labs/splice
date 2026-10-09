// NEW: V4-354 — the default request detail reads Claude Code's own redacted transcript by the response
// message id on its perf row. It is NOT a trace: the exact system prompt, tool definitions, raw
// request/answer bytes and headers remain behind the per-head opt-in TRACE (Knob.TRACE). The route
// serves only typed conversation messages. The off switch is checked BEFORE the source touches a
// file; it can answer off without reading any private conversation data.
package splice.head.trace

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.sessions.transcript.CONVERSATION_READ_TIMEOUT_MS
import splice.sessions.transcript.CONVERSATION_READ_UNAVAILABLE
import splice.sessions.transcript.MessageConversation
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.sessions.transcript.TranscriptMessageSource
import java.nio.file.Path

/** The approved config roots for a known head, in the session's priority order. Null means unknown
 *  head; a request cannot pick a directory by supplying a path. */
public fun interface TranscriptRoots {
    public fun forHead(head: String): List<Path>?
}

/** One guarded read of the conversation through the response the client received. */
public class TranscriptRequestRoute(
    private val source: TranscriptMessageSource,
    private val roots: TranscriptRoots,
    private val viewEnabled: SessionTranscriptViewEnabled,
    private val io: CoroutineDispatcher,
    private val readTimeoutMs: Long = CONVERSATION_READ_TIMEOUT_MS,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    public suspend fun read(head: String, sessionId: String?, responseId: String?): JsonReply {
        val approved = roots.forHead(head)
        return when {
            approved == null -> refuse(HttpStatusCode.BadRequest, "unknown head: $head")
            !viewEnabled() -> state("off", "Transcript view is off. Turn it on in Request detail.")
            sessionId.isNullOrBlank() || responseId.isNullOrBlank() ->
                refuse(HttpStatusCode.BadRequest, "a session and response id are required")
            else -> served(sessionId, responseId, approved)
        }
    }

    private suspend fun served(sessionId: String, responseId: String, approved: List<Path>): JsonReply {
        val read = withTimeoutOrNull(readTimeoutMs) {
            Cancellables.runCatchingCancellable {
                runInterruptible(io) { source.lookup(sessionId, approved, responseId) }
            }
        } ?: return state("unavailable", CONVERSATION_READ_UNAVAILABLE)
        val lookup = read.getOrElse { failure ->
            val diagnostic = "failure (message withheld: it may quote file bytes)" + SafeFailureText.site(failure)
            log("[transcript] saved transcript read failed: $diagnostic")
            return refuse(HttpStatusCode.InternalServerError, "Could not read this session's saved transcript.")
        }
        return when (lookup) {
            is MessageConversation.Found -> found(lookup)
            is MessageConversation.Missing -> state("missing", lookup.reason)
            is MessageConversation.Unavailable -> state("unavailable", lookup.reason)
            is MessageConversation.Refused -> refuse(HttpStatusCode.BadRequest, lookup.reason)
        }
    }

    private fun found(lookup: MessageConversation.Found): JsonReply {
        val body = buildJsonObject {
            put("state", "found")
            put("session_id", lookup.sessionId)
            put("response_message_id", lookup.responseId)
            putJsonArray("messages") {
                lookup.messages.forEach { message ->
                    addJsonObject {
                        put("index", message.index)
                        put("role", message.role.name.lowercase())
                        message.ts?.let { put("ts", it) }
                        put("text", message.text)
                        message.toolUse.name?.let { put("tool", it) }
                        message.toolUse.result?.let { put("result", it) }
                        message.toolUse.id?.let { put("tool_use_id", it) }
                        if (message.messageId == lookup.responseId) put("selected", true)
                    }
                }
            }
            put("earlier", lookup.earlier)
        }.let(JsonWire::string)
        return JsonReply(HttpStatusCode.OK, body)
    }

    private fun state(name: String, reason: String): JsonReply = JsonReply(
        HttpStatusCode.OK,
        buildJsonObject {
            put("state", name)
            put("reason", reason)
        }.let(JsonWire::string),
    )

    private fun refuse(status: HttpStatusCode, reason: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", reason) }.let(JsonWire::string))
}
