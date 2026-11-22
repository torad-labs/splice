// NEW: JSON codec for ccd_session buckets, index, tasks, and chapters.
package splice.modes.ccdsession

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars

private const val F_TASK_ID = "task_id"
private const val F_SESSION_ID = "session_id"
private const val F_TITLE = "title"
private const val F_TLDR = "tldr"
private const val F_PROMPT = "prompt"
private const val F_CWD = "cwd"
private const val F_STATE = "state"
private const val F_CREATED_AT = "created_at"
private const val F_STARTED_SESSION_ID = "started_session_id"
private const val F_CLAIMED_BY = "claimed_by"
private const val F_SUMMARY = "summary"

internal class CcdSessionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun taskJson(task: SuggestedTask): JsonObject = buildJsonObject {
        put(F_TASK_ID, task.taskId)
        put(F_SESSION_ID, task.sessionId)
        put(F_TITLE, task.content.title)
        put(F_TLDR, task.content.tldr)
        put(F_PROMPT, task.content.prompt)
        put(F_CWD, task.content.cwd?.let(::JsonPrimitive) ?: JsonNull)
        put(F_STATE, task.state.wire)
        put(F_CREATED_AT, task.createdAt)
        put(F_STARTED_SESSION_ID, task.startedSessionId?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun chapterJson(chapter: ChapterMark): JsonObject = buildJsonObject {
        put(F_SESSION_ID, chapter.sessionId)
        put(F_TITLE, chapter.title)
        put(F_SUMMARY, chapter.summary?.let(::JsonPrimitive) ?: JsonNull)
        put(F_CREATED_AT, chapter.createdAt)
    }

    fun encodeBucket(bucket: SessionBucket): String = buildJsonObject {
        put("active", buildJsonArray { bucket.active.forEach { add(encodeStoredTask(it)) } })
        put("resolved", buildJsonArray { bucket.resolved.forEach { add(encodeStoredTask(it)) } })
        put("chapters", buildJsonArray { bucket.chapters.forEach { add(chapterJson(it)) } })
    }.toString()

    fun decodeBucket(raw: String?): SessionBucket {
        val obj = raw?.let { JsonScalars.objectOrNull(json, it) } ?: return SessionBucket()
        val active = decodeTasks(obj["active"] as? JsonArray)
        val resolved = decodeTasks(obj["resolved"] as? JsonArray)
        val chapters = decodeChapters(obj["chapters"] as? JsonArray)
        return SessionBucket(active.toMutableList(), resolved.toMutableList(), chapters.toMutableList())
    }

    fun encodeIndex(index: GlobalIndex): String = buildJsonObject {
        put("sessions", buildJsonArray { index.sessions.forEach { add(JsonPrimitive(it)) } })
        put(
            "tasks",
            buildJsonObject {
                index.taskToSession.forEach { (id, session) -> put(id, session) }
            },
        )
    }.toString()

    fun decodeIndex(raw: String?): GlobalIndex {
        val obj = raw?.let { JsonScalars.objectOrNull(json, it) } ?: return GlobalIndex()
        val sessions = LinkedHashSet<String>()
        (obj["sessions"] as? JsonArray)?.forEach { elem ->
            JsonScalars.str(elem)?.takeIf(String::isNotBlank)?.let(sessions::add)
        }
        val map = LinkedHashMap<String, String>()
        (obj["tasks"] as? JsonObject)?.forEach { (k, v) ->
            JsonScalars.str(v)?.takeIf(String::isNotBlank)?.let { map[k] = it }
        }
        return GlobalIndex(sessions, map)
    }

    private fun encodeStoredTask(task: SuggestedTask): JsonObject = buildJsonObject {
        put(F_TASK_ID, task.taskId)
        put(F_SESSION_ID, task.sessionId)
        put(F_TITLE, task.content.title)
        put(F_TLDR, task.content.tldr)
        put(F_PROMPT, task.content.prompt)
        task.content.cwd?.let { put(F_CWD, it) }
        put(F_STATE, task.state.wire)
        put(F_CREATED_AT, task.createdAt)
        task.startedSessionId?.let { put(F_STARTED_SESSION_ID, it) }
        task.claimedBy?.let { put(F_CLAIMED_BY, it) }
    }

    private fun decodeTasks(array: JsonArray?): List<SuggestedTask> = array.orEmpty().mapNotNull { elem ->
        val obj = elem as? JsonObject ?: return@mapNotNull null
        val id = JsonScalars.str(obj, F_TASK_ID) ?: return@mapNotNull null
        val sessionId = JsonScalars.str(obj, F_SESSION_ID) ?: return@mapNotNull null
        val state = TaskState.entries.firstOrNull { it.wire == JsonScalars.str(obj, F_STATE) } ?: TaskState.PENDING
        val content = TaskContent(
            title = JsonScalars.str(obj, F_TITLE).orEmpty(),
            tldr = JsonScalars.str(obj, F_TLDR).orEmpty(),
            prompt = JsonScalars.str(obj, F_PROMPT).orEmpty(),
            cwd = JsonScalars.str(obj, F_CWD),
        )
        SuggestedTask(
            taskId = id,
            sessionId = sessionId,
            content = content,
            state = state,
            createdAt = JsonScalars.str(obj, F_CREATED_AT).orEmpty(),
            startedSessionId = JsonScalars.str(obj, F_STARTED_SESSION_ID),
            claimedBy = JsonScalars.str(obj, F_CLAIMED_BY),
        )
    }

    private fun decodeChapters(array: JsonArray?): List<ChapterMark> = array.orEmpty().mapNotNull { elem ->
        val obj = elem as? JsonObject ?: return@mapNotNull null
        val sessionId = JsonScalars.str(obj, F_SESSION_ID) ?: return@mapNotNull null
        val title = JsonScalars.str(obj, F_TITLE) ?: return@mapNotNull null
        ChapterMark(
            sessionId = sessionId,
            title = title,
            summary = JsonScalars.str(obj, F_SUMMARY),
            createdAt = JsonScalars.str(obj, F_CREATED_AT).orEmpty(),
        )
    }
}
