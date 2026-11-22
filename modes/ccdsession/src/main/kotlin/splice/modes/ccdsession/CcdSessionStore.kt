// NEW: thread-safe persistence for ccd_session suggested tasks and chapters.
package splice.modes.ccdsession

import splice.modes.api.ModeStore
import java.security.SecureRandom
import java.time.Instant

// why: task IDs are `task_` followed by 8 lowercase hex digits (4 random bytes).
private const val TASK_ID_BYTES = 4
private const val INDEX_KEY = "_index"

internal class CcdSessionStore(
    private val codec: CcdSessionCodec = CcdSessionCodec(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private val queue = CcdTaskQueue()

    fun spawn(store: ModeStore, sessionId: String, content: TaskContent): SpawnResult = synchronized(lock) {
        val index = codec.decodeIndex(store.read(INDEX_KEY))
        val bucket = codec.decodeBucket(store.read(sessionId))
        val evicted = queue.evictIfFull(bucket)
        val trimmed = content.copy(
            title = content.title.trim().take(MAX_TITLE_CHARS),
            tldr = content.tldr.trim().take(MAX_TLDR_CHARS),
        )
        val task = SuggestedTask(
            taskId = newTaskId(index),
            sessionId = sessionId,
            content = trimmed,
            state = TaskState.PENDING,
            createdAt = Instant.now().toString(),
        )
        bucket.active.add(task)
        index.sessions.add(sessionId)
        index.taskToSession[task.taskId] = sessionId
        save(store, sessionId, bucket, index)
        val position = bucket.active.count { it.state == TaskState.PENDING }
        SpawnResult(task, position, evicted, bucket.active.toList())
    }

    fun dismiss(store: ModeStore, sessionId: String, taskId: String): DismissOutcome = synchronized(lock) {
        val index = codec.decodeIndex(store.read(INDEX_KEY))
        val bucket = codec.decodeBucket(store.read(sessionId))
        val activeIdx = bucket.active.indexOfFirst { it.taskId == taskId }
        if (activeIdx >= 0) {
            val found = bucket.active[activeIdx]
            if (found.state == TaskState.IN_FLIGHT) return DismissOutcome.InFlight
            bucket.active.removeAt(activeIdx)
            queue.recordResolved(bucket, found.copy(state = TaskState.DISMISSED))
            save(store, sessionId, bucket, index)
            return DismissOutcome.Withdrawn
        }
        queue.priorDismissOutcome(bucket, taskId)
    }

    fun markChapter(store: ModeStore, sessionId: String, title: String, summary: String?): ChapterMark =
        synchronized(lock) {
            val index = codec.decodeIndex(store.read(INDEX_KEY))
            val bucket = codec.decodeBucket(store.read(sessionId))
            val mark = ChapterMark(
                sessionId = sessionId,
                title = title.trim(),
                summary = summary?.trim()?.takeIf(String::isNotEmpty),
                createdAt = Instant.now().toString(),
            )
            bucket.chapters.add(mark)
            index.sessions.add(sessionId)
            save(store, sessionId, bucket, index)
            mark
        }

    fun listTasks(store: ModeStore, sessionFilter: String?): List<SuggestedTask> = synchronized(lock) {
        sessionsFor(store, sessionFilter).flatMap { sid ->
            val bucket = codec.decodeBucket(store.read(sid))
            bucket.active + bucket.resolved
        }
    }

    fun listChapters(store: ModeStore, sessionFilter: String?): List<ChapterMark> = synchronized(lock) {
        sessionsFor(store, sessionFilter).flatMap { sid -> codec.decodeBucket(store.read(sid)).chapters }
    }

    fun claim(store: ModeStore, taskId: String, surface: String): ClaimOutcome = synchronized(lock) {
        val index = codec.decodeIndex(store.read(INDEX_KEY))
        val sessionId = index.taskToSession[taskId] ?: return ClaimOutcome.NotFound
        val bucket = codec.decodeBucket(store.read(sessionId))
        val activeIdx = bucket.active.indexOfFirst { it.taskId == taskId }
        if (activeIdx >= 0) {
            val task = bucket.active[activeIdx]
            if (task.state == TaskState.IN_FLIGHT) return ClaimOutcome.InFlight
            val claimed = task.copy(state = TaskState.IN_FLIGHT, claimedBy = surface)
            bucket.active[activeIdx] = claimed
            save(store, sessionId, bucket, index)
            return ClaimOutcome.Claimed(claimed)
        }
        if (bucket.resolved.any { it.taskId == taskId }) ClaimOutcome.NotPending else ClaimOutcome.NotFound
    }

    fun resolve(store: ModeStore, taskId: String, outcome: String, startedSessionId: String?): ResolveOutcome =
        synchronized(lock) {
            val index = codec.decodeIndex(store.read(INDEX_KEY))
            val sessionId = index.taskToSession[taskId] ?: return ResolveOutcome.NotFound
            val bucket = codec.decodeBucket(store.read(sessionId))
            val activeIdx = bucket.active.indexOfFirst { it.taskId == taskId }
            if (activeIdx < 0) return ResolveOutcome.NotFound
            val updated = queue.resolveActive(bucket, activeIdx, outcome, startedSessionId)
                ?: return ResolveOutcome.BadRequest("unknown outcome '$outcome'")
            save(store, sessionId, bucket, index)
            ResolveOutcome.Resolved(updated)
        }

    private fun sessionsFor(store: ModeStore, filter: String?): List<String> =
        if (filter != null) listOf(filter) else codec.decodeIndex(store.read(INDEX_KEY)).sessions.toList()

    private fun save(store: ModeStore, sessionId: String, bucket: SessionBucket, index: GlobalIndex) {
        store.write(sessionId, codec.encodeBucket(bucket))
        store.write(INDEX_KEY, codec.encodeIndex(index))
    }

    private fun newTaskId(index: GlobalIndex): String {
        val bytes = ByteArray(TASK_ID_BYTES)
        while (true) {
            random.nextBytes(bytes)
            val id = "task_" + bytes.joinToString("") { "%02x".format(it) }
            if (id !in index.taskToSession) return id
        }
    }
}
