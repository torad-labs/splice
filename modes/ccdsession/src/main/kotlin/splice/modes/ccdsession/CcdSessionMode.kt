// NEW: ccd-session mode handler (modes/ccdsession) — offers mcp__ccd_session__spawn_task,
// mcp__ccd_session__dismiss_task, and mcp__ccd_session__mark_chapter on every door session
// (except Claude Desktop, which supplies its own), and serves /api/suggested-tasks and /api/chapters.
package splice.modes.ccdsession

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.modes.api.Mode
import splice.modes.api.ModeAnswer
import splice.modes.api.ModeCall
import splice.modes.api.ModeRequest
import splice.modes.api.ModeResponse
import splice.modes.api.ModeRoute
import java.nio.file.Files
import java.nio.file.Path

private const val TOOL_SPAWN = "spawn_task"
private const val TOOL_DISMISS = "dismiss_task"
private const val TOOL_CHAPTER = "mark_chapter"

// why: `/api/suggested-tasks/{task_id}/{action}` has four non-empty path segments.
private const val TASK_ACTION_SEGMENTS = 4

private val TASK_ID_REGEX = Regex("^task_[0-9a-f]{8}$")

public class CcdSessionMode : Mode {
    private val codec = CcdSessionCodec()
    private val store = CcdSessionStore(codec)

    override fun answer(call: ModeCall): ModeAnswer = when (call.tool) {
        TOOL_SPAWN -> handleSpawn(call)
        TOOL_DISMISS -> handleDismiss(call)
        TOOL_CHAPTER -> handleChapter(call)
        else -> ModeAnswer.Error("unknown tool ${call.tool} in ccd_session mode")
    }

    override val routes: List<ModeRoute> = listOf(
        ModeRoute("GET", "/api/suggested-tasks", ::listTasks),
        ModeRoute("POST", "/api/suggested-tasks/{task_id}/claim", ::claimTask),
        ModeRoute("POST", "/api/suggested-tasks/{task_id}/resolve", ::resolveTask),
        ModeRoute("GET", "/api/chapters", ::listChapters),
    )

    private fun handleSpawn(call: ModeCall): ModeAnswer {
        val sessionId = call.session.id.takeIf(String::isNotBlank)
            ?: return ModeAnswer.Error("Session id is missing; cannot queue suggested task.")
        val title = JsonScalars.str(call.input, "title").orEmpty()
        val tldr = JsonScalars.str(call.input, "tldr").orEmpty()
        val prompt = JsonScalars.str(call.input, "prompt").orEmpty()
        val cwd = JsonScalars.str(call.input, "cwd")?.trim()?.takeIf(String::isNotEmpty)
        val validationError = validateSpawn(prompt, cwd)
        if (validationError != null) return ModeAnswer.Error(validationError)

        val result = store.spawn(call.store, sessionId, TaskContent(title, tldr, prompt, cwd))
        val evictedNote = formatEvicted(result.evicted)
        val pendingText = result.pending.joinToString(", ") { formatPendingItem(it) }
        return ModeAnswer.Text(
            "Queued suggested task ${result.task.taskId} at position ${result.position}. " +
                "The user can start it in a new session, run it here, or dismiss it. " +
                "Call dismiss_task with ${result.task.taskId} if this suggestion becomes stale. " +
                "${evictedNote}Pending queue: $pendingText. Continue with your current task.",
        )
    }

    private fun validateSpawn(prompt: String, cwd: String?): String? {
        if (prompt.isBlank()) return "prompt is required and must not be empty."
        if (prompt.length > MAX_PROMPT_CHARS) {
            return "prompt exceeds $MAX_PROMPT_CHARS characters (${prompt.length}); keep it focused."
        }
        return cwd?.let(::validateCwd)
    }

    private fun validateCwd(cwd: String): String? {
        val path = Path.of(cwd)
        val hasDotSegment = cwd.split('/', '\\').any { it == "." || it == ".." }
        return when {
            !path.isAbsolute -> "cwd must be an absolute path (got \"$cwd\"); omit cwd to use the current folder."
            hasDotSegment -> "cwd must not contain '.' or '..' segments; pass a clean absolute path."
            !Files.isDirectory(path) -> "cwd \"$cwd\" does not exist or is not a directory."
            else -> null
        }
    }

    private fun handleDismiss(call: ModeCall): ModeAnswer {
        val sessionId = call.session.id.takeIf(String::isNotBlank)
            ?: return ModeAnswer.Error("Session id is missing; cannot withdraw task.")
        val taskId = JsonScalars.str(call.input, "task_id").orEmpty().trim()
        if (!TASK_ID_REGEX.matches(taskId)) {
            return ModeAnswer.Error("task_id must match format task_xxxxxxxx returned by spawn_task.")
        }
        return when (store.dismiss(call.store, sessionId, taskId)) {
            DismissOutcome.Withdrawn ->
                ModeAnswer.Text("Withdrew suggested task $taskId from the queue. Continue with your current task.")
            DismissOutcome.AlreadyStarted ->
                ModeAnswer.Text("Task $taskId was already started or sent to this session; nothing was changed.")
            DismissOutcome.AlreadyDismissed ->
                ModeAnswer.Text("Task $taskId was already dismissed or displaced from the queue; do not re-queue it.")
            DismissOutcome.Expired ->
                ModeAnswer.Text("Task $taskId has already expired and is no longer shown; do not re-queue it.")
            DismissOutcome.InFlight ->
                ModeAnswer.Text("Task $taskId is currently being started and cannot be withdrawn; nothing was changed.")
            DismissOutcome.NotFound ->
                ModeAnswer.Text("No pending task with id $taskId was found in this session.")
        }
    }

    private fun handleChapter(call: ModeCall): ModeAnswer {
        val sessionId = call.session.id.takeIf(String::isNotBlank)
            ?: return ModeAnswer.Error("Session id is missing; cannot record chapter.")
        val title = JsonScalars.str(call.input, "title").orEmpty().trim()
        if (title.isEmpty()) return ModeAnswer.Error("title is required and must not be empty.")
        val summary = JsonScalars.str(call.input, "summary")
        val mark = store.markChapter(call.store, sessionId, title, summary)
        return ModeAnswer.Text("Recorded chapter \"${mark.title}\". Continue with your current task.")
    }

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
        return if (isTaskPath) parts[2].takeIf(TASK_ID_REGEX::matches) else null
    }

    private fun errorResponse(status: Int, code: String): ModeResponse =
        ModeResponse(status, buildJsonObject { put("error", JsonPrimitive(code)) })

    private fun formatEvicted(evicted: List<SuggestedTask>): String {
        if (evicted.isEmpty()) return ""
        val list = evicted.joinToString(", ") { formatPendingItem(it) }
        return "The queue holds at most $MAX_PENDING_TASKS pending tasks, so oldest items were displaced: $list. "
    }

    private fun formatPendingItem(task: SuggestedTask): String {
        val clean = task.content.title
            .replace("\r\n", " ⏎ ")
            .replace('\n', ' ')
            .replace('`', '\'')
            .replace("session_id", "session id")
            .replace("task_id", "task id")
            .take(MAX_LIST_TITLE_CHARS)
        return if (clean.isEmpty()) task.taskId else "${task.taskId} \"$clean\""
    }
}
