// NEW: Oct 12, 2026 — ending a session from the console, in any state (Marlin's p218 ruling, fin's words "End").
//
//   POST /api/sessions/{id}/end   {"confirm": true}   end the client where it runs
//
// TWO ACTS, NOT ONE. Stop halts a turn and keeps the agent; End closes the session, so after it the agent is gone
// (fin). End is Claude Code's own `/exit`, typed in its terminal as a person would, never a killed process.
//
// NOTHING IS LOST SILENTLY. Ending would cut a turn mid-work, or drop words typed into the prompt and not sent: then,
// without "confirm", nothing is pressed and the answer says what would go, the working state or the words verbatim,
// so the page arms and shows them (Marlin). With nothing to lose, End goes on the first ask. Confirmed, a running turn
// or a question on screen is stopped first (Escape does both), the prompt is emptied, and `/exit` is typed.
//
//   200  it ended: {"session_id", "state": "ended"}.   404  no such running session.
//   409  it would lose something (working, or draft with the words), or splice cannot reach its terminal.
//   503  no terminal.   504  its turn did not stop, or its client did not exit, in time.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.http.JsonReply
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource

// why: Claude Code's /exit flushes and quits within a second or two, as a move's exit does (SessionContinue).
private const val END_EXIT_SECONDS = 15L

// why: a stopped turn settles within a second; past this it is still running and /exit would be typed into it.
private const val END_STOP_SECONDS = 10L
private const val END_NOT_OURS = "splice did not start this session, so it cannot end it from here"
private const val END_CLOSED = "its terminal is closed, so it has already ended"

public class SessionEnd(
    private val drive: SessionDrive,
    private val handover: SessionHandover,
    private val registry: SessionSource?,
) {
    /** The client's own words for a turn in flight. */
    private val busy = setOf("working", "busy")

    /** POST .../end's body: {"confirm": true} ends it even when that cuts a turn or drops unsent words. */
    public suspend fun endJson(session: String, body: String): JsonReply =
        end(session, JsonScalars.objectOrNull(Json, body)?.let { JsonScalars.str(it, "confirm") } == "true")

    public suspend fun end(session: String, confirm: Boolean): JsonReply {
        val record = running(session)
        val driving = drive.driver()
        val at = driving?.let { drive.locate(it, session, END_NOT_OURS, END_CLOSED) }
        return when {
            record == null -> Refusals.reply(HttpStatusCode.NotFound, "no running session: $session", Refusal.ENDED)
            driving == null -> Refusals.reply(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_DRIVE)
            at?.pane == null -> requireNotNull(at?.refusal)
            else -> (if (confirm) null else cost(record)) ?: close(driving, at.pane, record)
        }
    }

    /** What ending [session] would lose, as the refusal that names it, or null when nothing would go. */
    public fun cost(session: String): JsonReply? = running(session)?.let(::cost)

    private fun cost(record: SessionRecord): JsonReply? =
        if (record.status.state in busy) {
            Refusals.reply(HttpStatusCode.Conflict, "it is working; ending it cuts the turn", Refusal.WORKING)
        } else {
            drive.drafted(record.sessionId)
        }

    private fun running(session: String): SessionRecord? = registry?.read().orEmpty()
        .firstOrNull { it.sessionId == session && it.availability != SessionAvailability.GONE }

    /** Stop what runs or asks, empty the prompt, type `/exit`, and wait for the client to go. */
    private suspend fun close(driving: SessionDriver, pane: SessionPane, record: SessionRecord): JsonReply =
        halt(driving, pane, record) ?: exit(driving, pane, record)

    /** Stop a turn in flight or a question on screen (Escape does both); null once nothing runs or asks. */
    private suspend fun halt(driving: SessionDriver, pane: SessionPane, record: SessionRecord): JsonReply? {
        val working = record.status.state in busy
        if (!working && record.status.waitingFor == null) return null
        val failed = Cancellables.runCatchingCleanup { driving.terminal.press(pane, SessionKey.STOP) }.exceptionOrNull()
        return when {
            failed != null -> refused()
            working && !handover.stopped(record.sessionId, END_STOP_SECONDS) ->
                Refusals.reply(HttpStatusCode.GatewayTimeout, "its turn did not stop within ${END_STOP_SECONDS}s")
            else -> null
        }
    }

    /** Empty the prompt, type `/exit`, and wait for the client to go. */
    private suspend fun exit(driving: SessionDriver, pane: SessionPane, record: SessionRecord): JsonReply {
        val failed = Cancellables.runCatchingCleanup {
            driving.terminal.press(pane, SessionKey.CLEAR)
            driving.terminal.send(pane, "/exit")
        }.exceptionOrNull()
        return when {
            failed != null -> refused()
            !exited(driving, pane, record) ->
                Refusals.reply(HttpStatusCode.GatewayTimeout, "its client did not exit within ${END_EXIT_SECONDS}s")
            else -> {
                driving.panes.forget(record.sessionId)
                JsonReply(
                    HttpStatusCode.OK,
                    buildJsonObject {
                        put("session_id", record.sessionId)
                        put("state", "ended")
                    }.toString(),
                )
            }
        }
    }

    private fun refused(): JsonReply =
        Refusals.reply(HttpStatusCode.Conflict, "the terminal did not take it", Refusal.REFUSED)

    /** Whether the client is gone: its process left, or, with no process known, its terminal closed. */
    private suspend fun exited(driving: SessionDriver, pane: SessionPane, record: SessionRecord): Boolean =
        record.process.pid?.let { handover.left(record.sessionId, it, END_EXIT_SECONDS) }
            ?: !driving.terminal.isOpen(pane)
}
