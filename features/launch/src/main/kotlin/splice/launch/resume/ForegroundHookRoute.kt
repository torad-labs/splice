// NEW: opaque foreground callbacks share the resume hook's session-authenticated local transport.
package splice.launch.resume

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import splice.core.client.DOOR_HOOK_KEY
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
        handle(
            call.parameters["head"].orEmpty(),
            jsonBody.parse(call),
            call.request.headers[FOREGROUND_OWNER_HEADER],
            call.request.queryParameters["phase"],
        )
        call.respondText("{}", ContentType.Application.Json)
    }

    private data class ActivityCall(val session: String, val tool: String?, val phase: ForegroundToolPhase)

    internal fun handle(key: String, body: JsonObject?, owner: String?, hookPhase: String? = null) {
        if (knownTarget(key) && owner?.length == FOREGROUND_OWNER_LENGTH) {
            val event = body?.let { parse(key, it, hookPhase) } ?: return
            activity.record(ForegroundToolCall(event.session, event.tool, event.phase, owner))
        }
    }

    private fun knownTarget(key: String): Boolean = key == DOOR_HOOK_KEY || heads.byKey(key) != null

    private fun parse(key: String, body: JsonObject, hookPhase: String?): ActivityCall? {
        val session = JsonScalars.strIfString(body["session_id"])
            .takeIf { it.isNotEmpty() && it.length <= MAX_SESSION_ID_CHARS } ?: return null
        return phase(key, body, hookPhase)?.let { toolCall(session, it, body) }
    }

    private fun phase(key: String, body: JsonObject, hookPhase: String?): ForegroundToolPhase? = when {
        hookPhase == null -> body.takeIf { it.keys == FOREGROUND_FIELDS }?.let(::phase)
        key != DOOR_HOOK_KEY -> null
        hookPhase == "start" -> ForegroundToolPhase.START
        hookPhase == "end" -> ForegroundToolPhase.END
        else -> null
    }

    private fun phase(body: JsonObject): ForegroundToolPhase? = when (JsonScalars.strIfString(body["phase"])) {
        "session_start" -> ForegroundToolPhase.SESSION_START
        "start" -> ForegroundToolPhase.START
        "end" -> ForegroundToolPhase.END
        "session_end" -> ForegroundToolPhase.SESSION_END
        else -> null
    }

    private fun toolCall(session: String, phase: ForegroundToolPhase, body: JsonObject): ActivityCall? {
        val tool = JsonScalars.strIfString(body["tool_use_id"])
            .takeIf { it.isNotEmpty() && it.length <= MAX_TOOL_ID_CHARS }
        return when (phase) {
            ForegroundToolPhase.SESSION_START, ForegroundToolPhase.SESSION_END ->
                if (body["tool_use_id"] == kotlinx.serialization.json.JsonNull) {
                    ActivityCall(session, null, phase)
                } else {
                    null
                }
            ForegroundToolPhase.START, ForegroundToolPhase.END -> tool?.let { ActivityCall(session, it, phase) }
        }
    }
}
