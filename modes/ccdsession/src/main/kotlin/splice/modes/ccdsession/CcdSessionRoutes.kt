// NEW: HTTP routes for ccd_session suggested tasks and chapters (/api/suggested-tasks, /api/chapters).
package splice.modes.ccdsession

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse
import splice.modes.api.ModeRoute

// why: `/api/suggested-tasks/{task_id}/{action}` has four non-empty path segments.
private const val TASK_ACTION_SEGMENTS = 4

internal val ccdTaskIdRegex: Regex = Regex("^task_[0-9a-f]{8}$")

internal class CcdSessionRoutes(
    private val store: CcdSessionStore,
    private val codec: CcdSessionCodec,
) {
    fun all(): List<ModeRoute> = listOf(
        ModeRoute("GET", "/api/suggested-tasks", ::listTasks),
        ModeRoute("POST", "/api/suggested-tasks/{task_id}/claim", ::claimTask),
        ModeRoute("POST", "/api/suggested-tasks/{task_id}/resolve", ::resolveTask),
        ModeRoute("GET", "/api/chapters", ::listChapters),
    )

    private fun listTasks(request: ModeRequest): ModeResponse {
        val session = request.query["session"]?.takeIf(String::isNotBlank)
        val tasks = store.listTasks(request.store, session).map(codec::taskJson)
        return ModeResponse(HttpStatus.OK, buildJsonObject { put("tasks", JsonArray(tasks)) })
    }

    private fun listChapters(request: ModeRequest): ModeResponse {
        val session = request.query["session"]?.takeIf(String::isNotBlank)
        val chapters = store.listChapters(request.store, session).map(codec::chapterJson)
        return ModeResponse(HttpStatus.OK, buildJsonObject { put("chapters", JsonArray(chapters)) })
    }

    private fun claimTask(request: ModeRequest): ModeResponse {
        val taskId = extractTaskId(request.path)
            ?: return errorResponse(HttpStatus.BAD_REQUEST, "invalid_task_id")
        val body = request.body as? JsonObject
        val surface = JsonScalars.str(body?.get("surface"))?.takeIf(String::isNotBlank) ?: "unknown"
        return when (val result = store.claim(request.store, taskId, surface)) {
            is ClaimOutcome.Claimed -> ModeResponse(
                HttpStatus.OK,
                buildJsonObject { put("task", codec.taskJson(result.task)) },
            )
            ClaimOutcome.InFlight -> errorResponse(HttpStatus.CONFLICT, "in_flight")
            ClaimOutcome.NotPending -> errorResponse(HttpStatus.CONFLICT, "not_pending")
            ClaimOutcome.NotFound -> errorResponse(HttpStatus.NOT_FOUND, "not_found")
        }
    }

    private fun resolveTask(request: ModeRequest): ModeResponse {
        val taskId = extractTaskId(request.path)
            ?: return errorResponse(HttpStatus.BAD_REQUEST, "invalid_task_id")
        val body = request.body as? JsonObject
            ?: return errorResponse(HttpStatus.BAD_REQUEST, "body_required")
        val outcome = JsonScalars.str(body, "outcome")?.trim().orEmpty()
        val startedSessionId = JsonScalars.str(body, "session_id")?.takeIf(String::isNotBlank)
        return when (val result = store.resolve(request.store, taskId, outcome, startedSessionId)) {
            is ResolveOutcome.Resolved -> ModeResponse(
                HttpStatus.OK,
                buildJsonObject { put("task", codec.taskJson(result.task)) },
            )
            is ResolveOutcome.BadRequest -> errorResponse(HttpStatus.BAD_REQUEST, result.error)
            ResolveOutcome.NotFound -> errorResponse(HttpStatus.NOT_FOUND, "not_found")
        }
    }

    private fun extractTaskId(path: String): String? {
        val parts = path.split('/').filter(String::isNotEmpty)
        if (parts.size < TASK_ACTION_SEGMENTS) return null
        val isTaskPath = parts[0] == "api" && parts[1] == "suggested-tasks"
        return if (isTaskPath) parts[2].takeIf(ccdTaskIdRegex::matches) else null
    }

    private fun errorResponse(status: Int, code: String): ModeResponse =
        ModeResponse(status, buildJsonObject { put("error", JsonPrimitive(code)) })
}
