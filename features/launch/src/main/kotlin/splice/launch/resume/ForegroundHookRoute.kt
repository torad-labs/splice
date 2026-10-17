// NEW: opaque foreground callbacks share the resume hook's session-authenticated local transport.
package splice.launch.resume

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import splice.core.client.FOREGROUND_OWNER_HEADER
import splice.core.client.FOREGROUND_OWNER_LENGTH
import splice.core.client.ForegroundToolActivity
import splice.core.client.ForegroundToolCall
import splice.core.client.ForegroundToolPhase
import splice.core.util.JsonScalars
import splice.http.JsonBody
import splice.launch.LaunchHeads

private val FOREGROUND_FIELDS = setOf("session_id", "tool_use_id", "phase")

// why: opaque session ids are map keys, not paths; bound retained text without assuming their contents.
private const val MAX_SESSION_ID_CHARS = 128

// why: opaque tool ids need room for provider spellings while each retained callback remains bounded.
private const val MAX_TOOL_ID_CHARS = 256

/** Invalid activity is ignored without logging tool input or delaying its execution. */
public class ForegroundHookRoute(
    private val heads: LaunchHeads,
    private val activity: ForegroundToolActivity,
    private val jsonBody: JsonBody = JsonBody(),
) {
    public suspend fun receive(call: ApplicationCall) {
        handle(call.parameters["head"].orEmpty(), jsonBody.parse(call), call.request.headers[FOREGROUND_OWNER_HEADER])
        call.respondText("{}", ContentType.Application.Json)
    }

    private data class ActivityCall(val session: String, val tool: String?, val phase: ForegroundToolPhase)

    internal fun handle(key: String, body: JsonObject?, owner: String?) {
        if (heads.byKey(key) == null || owner == null) return
        val event = body?.let(::parse) ?: return
        if (owner.length == FOREGROUND_OWNER_LENGTH) {
            activity.record(ForegroundToolCall(event.session, event.tool, event.phase, owner))
        }
    }

    private fun parse(body: JsonObject): ActivityCall? {
        if (body.keys != FOREGROUND_FIELDS) return null
        val session = JsonScalars.strIfString(body["session_id"])
            .takeIf { it.isNotEmpty() && it.length <= MAX_SESSION_ID_CHARS }
        val phase = phase(body)
        return if (session != null && phase != null) toolCall(session, phase, body) else null
    }

    private fun phase(body: JsonObject): ForegroundToolPhase? = when (JsonScalars.strIfString(body["phase"])) {
        "start" -> ForegroundToolPhase.START
        "end" -> ForegroundToolPhase.END
        "session_end" -> ForegroundToolPhase.SESSION_END
        else -> null
    }

    private fun toolCall(session: String, phase: ForegroundToolPhase, body: JsonObject): ActivityCall? {
        val tool = JsonScalars.strIfString(body["tool_use_id"])
            .takeIf { it.isNotEmpty() && it.length <= MAX_TOOL_ID_CHARS }
        return when (phase) {
            ForegroundToolPhase.SESSION_END ->
                if (body["tool_use_id"] == kotlinx.serialization.json.JsonNull) {
                    ActivityCall(session, null, phase)
                } else {
                    null
                }
            ForegroundToolPhase.START, ForegroundToolPhase.END -> tool?.let { ActivityCall(session, it, phase) }
            ForegroundToolPhase.SESSION_START -> null
        }
    }
}
