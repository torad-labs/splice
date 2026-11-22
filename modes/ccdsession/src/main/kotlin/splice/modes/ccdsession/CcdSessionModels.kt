// NEW: ccd-session mode data structures and bounds (modes/ccdsession).
package splice.modes.ccdsession

// why: matches the Desktop suggested-task queue cap so pending chips never grow unbounded per session.
internal const val MAX_PENDING_SUGGESTIONS = 20

// why: keeps the last 100 resolved task records per session (contract with the Eli bridge & dismiss_task).
internal const val MAX_RESOLVED_TASKS = 100

// why: matches the Desktop schema maximum length for a suggested task title.
internal const val MAX_TITLE_CHARS = 200

// why: matches the Desktop schema maximum length for a suggested task tldr summary.
internal const val MAX_TLDR_CHARS = 2000

// why: matches the Desktop schema maximum length for a suggested task prompt body.
internal const val MAX_PROMPT_CHARS = 32_000

// why: caps each pending item's title in spawn_task's summary line so tool output stays readable.
internal const val MAX_LIST_TITLE_CHARS = 120

internal enum class TaskState(val wire: String) {
    PENDING("pending"),
    IN_FLIGHT("in_flight"),
    STARTED("started"),
    HERE("here"),
    DISMISSED("dismissed"),
    EXPIRED("expired"),
}

internal data class TaskContent(
    val title: String,
    val tldr: String,
    val prompt: String,
    val cwd: String?,
)

internal data class SuggestedTask(
    val taskId: String,
    val sessionId: String,
    val content: TaskContent,
    val state: TaskState,
    val createdAt: String,
    val startedSessionId: String? = null,
    val claimedBy: String? = null,
)

internal data class ChapterMark(
    val sessionId: String,
    val title: String,
    val summary: String?,
    val createdAt: String,
)

internal data class SessionBucket(
    val active: MutableList<SuggestedTask> = mutableListOf(),
    val resolved: MutableList<SuggestedTask> = mutableListOf(),
    val chapters: MutableList<ChapterMark> = mutableListOf(),
)

internal data class GlobalIndex(
    val sessions: LinkedHashSet<String> = LinkedHashSet(),
    val taskToSession: LinkedHashMap<String, String> = LinkedHashMap(),
)
