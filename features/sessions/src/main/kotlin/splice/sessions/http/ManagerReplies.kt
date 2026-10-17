// NEW: Oct 15, 2026 (the splice manager) — what GET /api/manager and both POSTs answer, and how they refuse.
//
//   { "folder", "consented", "state": "off" | "resumable" | "live", "head", "session_id",
//     "status": "working" | "idle" | "waiting" | null, "heads": [ { "key", "label", "ready", "why" } ],
//     "line": { "model", "model_id", "effort", "context": { "used", "window", "percent", "cache" } | null, "at" } | null }
//
// live: the manager's client runs (registered, or a pane splice opened that asks something before it registers, which
// reads "waiting"). resumable: nothing runs and a transcript is there. off: nothing yet. consented: his yes is on
// record, or a manager runs in the folder, which he started there himself. heads: the head it runs on (or will) first,
// then the rest as GET /api/heads lists them. line: what the manager's status line last said (SessionLines), null while
// it does not run or has not ticked since the daemon started; context is null before its first turn. A refusal is the console's { "error", "reason" } (Refusals).
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.session.SessionLine
import splice.core.session.SessionLines
import splice.http.JsonReply
import splice.sessions.registry.SessionRecord
import java.nio.file.Path

private const val MANAGER_WAITING_STATUS = "waiting"

/** One head the person can pick; [why] says in plain words why it cannot carry the manager now, when [ready] is
 *  false. */
@Serializable
public data class ManagerHeadChoice(
    val key: String,
    val label: String,
    val ready: Boolean,
    val why: String?,
)

/** How full the manager's context is, as its status line prints it: tokens used of the window, the percent, and the
 *  share of the input the cache served (null before it read any). */
@Serializable
internal data class ManagerContext(val used: Long, val window: Long, val percent: Int, val cache: Int?)

/** What the manager's status line last said, and when splice read it ([at], epoch milliseconds). */
@Serializable
internal data class ManagerLine(
    val model: String?,
    @SerialName("model_id") val modelId: String?,
    val effort: String?,
    val context: ManagerContext?,
    val at: Long,
)

/** The manager as the composer draws it. */
@Serializable
internal data class ManagerState(
    val folder: String,
    val consented: Boolean,
    val state: String,
    val head: String?,
    @SerialName("session_id") val sessionId: String?,
    val status: String?,
    val heads: List<ManagerHeadChoice>,
    val line: ManagerLine?,
)

internal class ManagerReplies(
    private val lookup: ManagerLookup,
    private val pointers: ManagerPointerStore,
    private val heads: ManagerHeads,
    private val lines: SessionLines = SessionLines(),
) {
    /** Every field is written, a null one as null, so the page never guesses at a missing key. */
    private val json = Json { explicitNulls = true }

    /** Claude Code's own words for a turn in flight. */
    private val busy = setOf("working", "busy")

    /** The state as it is now, read afresh: the pointer, the registry, the panes and the transcripts. */
    fun state(folder: Path, status: HttpStatusCode = HttpStatusCode.OK): JsonReply =
        JsonReply(status, json.encodeToString(ManagerState.serializer(), snapshot(folder)))

    fun refuse(sentence: String, reason: Refusal): JsonReply = Refusals.reply(HttpStatusCode.Conflict, sentence, reason)

    /** [head] when splice runs a head by that key the manager can start on, else null. */
    fun known(head: String?): String? = head?.takeIf { key -> heads.heads().any { it.key == key } }

    private fun snapshot(folder: Path): ManagerState {
        val pointer = pointers.read()
        val found = lookup.find(folder, pointer)
        val live = found as? ManagerFound.Live
        val head = live?.record?.head ?: pointer?.head
        val (state, session) = when (found) {
            is ManagerFound.Live -> "live" to found.session
            is ManagerFound.Resumable -> "resumable" to found.session
            ManagerFound.Off -> "off" to null
        }
        return ManagerState(
            folder = folder.toString(),
            consented = pointer?.consented == true || live != null,
            state = state,
            head = head,
            sessionId = session,
            status = live?.let { statusOf(it.record) },
            heads = ordered(heads.heads(), head),
            line = live?.let { lines.of(session)?.let(::lineOf) },
        )
    }

    private fun lineOf(tick: SessionLine) = ManagerLine(
        model = tick.model,
        modelId = tick.modelId,
        effort = tick.effort,
        context = tick.context?.let { ManagerContext(it.usedTokens, it.windowTokens, it.percent, it.cachePercent) },
        at = tick.atMs,
    )

    /** A pane still asking before Claude Code registers waits on him, as a registered session waiting on an answer
     *  does. */
    private fun statusOf(record: SessionRecord?): String = when {
        record == null || record.status.waitingFor != null -> MANAGER_WAITING_STATUS
        record.status.state == MANAGER_WAITING_STATUS -> MANAGER_WAITING_STATUS
        record.status.state in busy -> "working"
        else -> "idle"
    }

    private fun ordered(choices: List<ManagerHeadChoice>, head: String?): List<ManagerHeadChoice> {
        val (first, rest) = choices.partition { it.key == head }
        return first + rest
    }
}
