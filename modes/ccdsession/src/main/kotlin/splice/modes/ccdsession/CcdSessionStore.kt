// NEW: thread-safe persistence and state transitions for ccd_session suggested tasks and chapters.
package splice.modes.ccdsession

import splice.modes.api.ModeStore
import java.security.SecureRandom
import java.time.Instant

// why: task IDs are `task_` followed by 8 lowercase hex digits (4 random bytes).
private const val TASK_ID_BYTES = 4
private const val INDEX_KEY = "_index"

internal sealed class DismissOutcome {
    data object Withdrawn : DismissOutcome()
    data object AlreadyStarted : DismissOutcome()
    data object AlreadyDismissed : DismissOutcome()
    data object Expired : DismissOutcome()
    data object InFlight : DismissOutcome()
    data object NotFound : DismissOutcome()
}

internal sealed class ClaimOutcome {
    data class Claimed(val task: SuggestedTask) : ClaimOutcome()
    data object InFlight : ClaimOutcome()
    data object NotPending : ClaimOutcome()
    data object NotFound : ClaimOutcome()
}

internal sealed class ResolveOutcome {
    data class Resolved(val task: SuggestedTask) : ResolveOutcome()
    data object NotFound : ResolveOutcome()
    data class BadRequest(val error: String) : ResolveOutcome()
}

internal data class SpawnResult(
    val task: SuggestedTask,
    val position: Int,
    val evicted: List<SuggestedTask>,
    val pending: List<SuggestedTask>,
)

internal class CcdSessionStore(
    private val codec: CcdSessionCodec = CcdSessionCodec(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()

    fun spawn(store: ModeStore, sessionId: String, content: TaskContent): SpawnResult = synchronized(lock) {
        val index = codec.decodeIndex(store.read(INDEX_KEY))
        val bucket = codec.decodeBucket(store.read(sessionId))
        val evicted = evictIfFull(bucket)
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
            recordResolved(bucket, found.copy(state = TaskState.DISMISSED))
            save(store, sessionId, bucket, index)
            return DismissOutcome.Withdrawn
        }
        val prior = bucket.resolved.lastOrNull { it.taskId == taskId }
        when (prior?.state) {
            TaskState.STARTED, TaskState.HERE -> DismissOutcome.AlreadyStarted
            TaskState.DISMISSED -> DismissOutcome.AlreadyDismissed
            TaskState.EXPIRED -> DismissOutcome.Expired
            TaskState.PENDING, TaskState.IN_FLIGHT, null -> DismissOutcome.NotFound
        }
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
        val sessions = if (sessionFilter != null) {
            listOf(sessionFilter)
        } else {
            codec.decodeIndex(store.read(INDEX_KEY)).sessions.toList()
        }
        sessions.flatMap { sid ->
            val bucket = codec.decodeBucket(store.read(sid))
            bucket.active + bucket.resolved
        }
    }

    fun listChapters(store: ModeStore, sessionFilter: String?): List<ChapterMark> = synchronized(lock) {
        val sessions = if (sessionFilter != null) {
            listOf(sessionFilter)
        } else {
            codec.decodeIndex(store.read(INDEX_KEY)).sessions.toList()
        }
        sessions.flatMap { sid -> codec.decodeBucket(store.read(sid)).chapters }
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
        val inResolved = bucket.resolved.any { it.taskId == taskId }
        if (inResolved) ClaimOutcome.NotPending else ClaimOutcome.NotFound
    }

    fun resolve(
        store: ModeStore,
        taskId: String,
        outcome: String,
        startedSessionId: String?,
    ): ResolveOutcome = synchronized(lock) {
        val index = codec.decodeIndex(store.read(INDEX_KEY))
        val sessionId = index.taskToSession[taskId] ?: return ResolveOutcome.NotFound
        val bucket = codec.decodeBucket(store.read(sessionId))
        val activeIdx = bucket.active.indexOfFirst { it.taskId == taskId }
        if (activeIdx < 0) return ResolveOutcome.NotFound
        val updated = nextResolvedTask(bucket, activeIdx, outcome, startedSessionId)
            ?: return ResolveOutcome.BadRequest("unknown outcome '$outcome'")
        save(store, sessionId, bucket, index)
        ResolveOutcome.Resolved(updated)
    }

    private fun nextResolvedTask(
        bucket: SessionBucket,
        activeIdx: Int,
        outcome: String,
        startedSessionId: String?,
    ): SuggestedTask? {
        val task = bucket.active[activeIdx]
        if (outcome == "released") {
            val pending = task.copy(state = TaskState.PENDING, claimedBy = null)
            bucket.active[activeIdx] = pending
            return pending
        }
        val nextState = when (outcome) {
            "started" -> TaskState.STARTED
            "here" -> TaskState.HERE
            "dismissed" -> TaskState.DISMISSED
            "expired" -> TaskState.EXPIRED
            else -> return null
        }
        bucket.active.removeAt(activeIdx)
        val finished = task.copy(
            state = nextState,
            startedSessionId = if (nextState == TaskState.STARTED) startedSessionId else null,
            claimedBy = null,
        )
        recordResolved(bucket, finished)
        return finished
    }

    private fun evictIfFull(bucket: SessionBucket): List<SuggestedTask> {
        val evicted = mutableListOf<SuggestedTask>()
        while (bucket.active.size >= MAX_PENDING_TASKS) {
            val victimIdx = bucket.active.indexOfFirst { it.state == TaskState.PENDING }
            if (victimIdx < 0) break
            val victim = bucket.active.removeAt(victimIdx).copy(state = TaskState.DISMISSED)
            recordResolved(bucket, victim)
            evicted.add(victim)
        }
        return evicted
    }

    private fun recordResolved(bucket: SessionBucket, task: SuggestedTask) {
        bucket.resolved.add(task)
        while (bucket.resolved.size > MAX_RESOLVED_TASKS) {
            bucket.resolved.removeAt(0)
        }
    }

    private fun save(store: ModeStore, sessionId: String, bucket: SessionBucket, index: GlobalIndex) {
        store.write(sessionId, codec.encodeBucket(bucket))
        store.write(INDEX_KEY, codec.encodeIndex(index))
    }

    private fun newTaskId(index: GlobalIndex): String {
        val bytes = ByteArray(TASK_ID_BYTES)
        while (true) {
            random.nextBytes(bytes)
            val hex = bytes.joinToString("") { "%02x".format(it) }
            val id = "task_$hex"
            if (id !in index.taskToSession) return id
        }
    }
}
