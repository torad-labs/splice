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
package splice.sessions.end

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.http.JsonReply
import splice.sessions.http.NO_TERMINAL_TO_DRIVE
import splice.sessions.http.PaneTurns
import splice.sessions.http.Refusal
import splice.sessions.http.Refusals
import splice.sessions.http.SessionDrive
import splice.sessions.http.SessionDriver
import splice.sessions.http.SessionHandover
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionSource

// why: Claude Code's /exit flushes and quits within a second or two, as a move's exit does (SessionContinue).
private const val END_EXIT_SECONDS = 15L

// why: a stopped turn settles within a second; past this it is still running and /exit would be typed into it.
private const val END_STOP_SECONDS = 10L
private const val END_NOT_OURS = "splice did not start this session, so it cannot end it from here"
private const val END_CLOSED = "its terminal is closed, so it has already ended"
private const val END_ACTED =
    "it was given something while End waited its turn; ending it now may cut what that started"
private const val END_REPLACED =
    "its client is not the one End found: it ended or was started again while End waited its turn, so nothing was typed"

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
        // What End decides on, counted before anything reads the session. Unconfirmed, it is looked at again in the
        // pane's turn before `/exit` goes, so an act landing after this count stops it, before or after the read.
        val seen = drive.turns.acts(session).takeUnless { confirm }
        val record = running(session)
        val driving = drive.driver()
        val at = driving?.let { drive.locate(it, session, END_NOT_OURS, END_CLOSED) }
        return when {
            record == null -> Refusals.reply(HttpStatusCode.NotFound, "no running session: $session", Refusal.ENDED)
            driving == null -> Refusals.reply(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_DRIVE)
            at?.pane == null -> requireNotNull(at?.refusal)
            else -> (if (confirm) null else cost(record)) ?: close(driving, at.pane, record, seen)
        }
    }

    /** What ending [session] would lose, as the refusal that names it, or null when nothing would go. A prompt whose
     *  screen cannot be read is refused too (SessionDrive.drafted): unsent words splice could not see are never
     *  emptied unasked. */
    public fun cost(session: String): JsonReply? = running(session)?.let(::cost)

    private fun cost(record: SessionRecord): JsonReply? =
        if (record.status.state in busy) {
            Refusals.reply(HttpStatusCode.Conflict, "it is working; ending it cuts the turn", Refusal.WORKING)
        } else {
            drive.drafted(record.sessionId.orEmpty())
        }

    private fun running(session: String): SessionRecord? = registry?.read().orEmpty()
        .firstOrNull { it.sessionId == session && it.availability != SessionAvailability.GONE }

    /** Stop what runs or asks, empty the prompt, type `/exit`, and wait for the client to go. Every key is typed in
     *  the pane's turn, the one a message and an answer take, with the session found in that pane again first.
     *  [seen] is the session's act count End decided on, null once he confirmed. */
    private suspend fun close(
        driving: SessionDriver,
        pane: SessionPane,
        record: SessionRecord,
        seen: Long?,
    ): JsonReply = halt(driving, pane, record, seen) ?: exit(driving, pane, record, seen)

    /** Stop a turn in flight or a question on screen (Escape does both); null once nothing runs or asks. Escape goes in
     *  the pane's turn only while nothing [moved]: a turn started since End looked is not the one it would cut. */
    private suspend fun halt(
        driving: SessionDriver,
        pane: SessionPane,
        record: SessionRecord,
        seen: Long?,
    ): JsonReply? {
        val working = record.status.state in busy
        if (!working && record.status.waitingFor == null) return null
        val session = record.sessionId.orEmpty()
        val pressed = drive.turns.ending(pane, session, again(driving, record)) {
            moved(record, seen) ?: Cancellables.runCatchingCleanup { driving.terminal.press(pane, SessionKey.STOP) }
                .exceptionOrNull()?.let { refused() }
        }
        return when {
            pressed != null -> pressed
            working && !handover.stopped(session, END_STOP_SECONDS) ->
                Refusals.reply(HttpStatusCode.GatewayTimeout, "its turn did not stop within ${END_STOP_SECONDS}s")
            else -> null
        }
    }

    /** Empty the prompt, type `/exit`, and wait for the client to go, with nothing else typed into the pane until it
     *  has. The keys go only while nothing [moved], to the client the mark and the wait name. An End he did not
     *  confirm ([seen] set) also stops for a turn it runs now or words in its prompt, which are his, the way it would
     *  have on the first look ([cost]). A client that does not leave in time keeps the
     *  pane marked as being left, since it may still be going. */
    private suspend fun exit(driving: SessionDriver, pane: SessionPane, record: SessionRecord, seen: Long?): JsonReply {
        val session = record.sessionId.orEmpty()
        val exit = object : PaneTurns.Exit {
            override val pid: Long? = record.process.pid

            override fun check(): JsonReply? = moved(record, seen) ?: seen?.let { cost(session) }

            override fun keys(): JsonReply? = Cancellables.runCatchingCleanup {
                driving.terminal.press(pane, SessionKey.CLEAR)
                driving.terminal.send(pane, "/exit")
            }.exceptionOrNull()?.let { refused() }

            override suspend fun left(): Boolean = exited(driving, pane, record)

            override fun stayed(): JsonReply =
                Refusals.reply(HttpStatusCode.GatewayTimeout, "its client did not exit within ${END_EXIT_SECONDS}s")
        }
        drive.turns.leave(pane, session, again(driving, record), exit)?.let { return it }
        driving.panes.forget(session)
        return JsonReply(HttpStatusCode.OK, "{}")
    }

    /** The refusal when the session moved on since End looked, read in the pane's turn before a key goes: its running
     *  client is not [record]'s process, as when the same session was resumed in that pane meanwhile, even under the
     *  same pid, which the pane finds by session and not by process, so an Escape, `/exit` or wait meant for the
     *  first would reach or name the wrong one; or, unconfirmed ([seen] set), it was given something since End counted
     *  [seen] acts on it (PR #247's sixth and seventh reviews). */
    private fun moved(record: SessionRecord, seen: Long?): JsonReply? {
        val session = record.sessionId.orEmpty()
        return when {
            !record.process.sameAs(running(session)?.process) ->
                Refusals.reply(HttpStatusCode.Conflict, END_REPLACED, Refusal.PANE_TAKEN)
            seen != null && drive.turns.acts(session) != seen ->
                Refusals.reply(HttpStatusCode.Conflict, END_ACTED, Refusal.WORKING)
            else -> null
        }
    }

    /** [record]'s session located anew, as its pane's turn checks it before a key goes. */
    private fun again(driving: SessionDriver, record: SessionRecord): PaneTurns.Relocate =
        PaneTurns.Relocate { drive.locate(driving, record.sessionId.orEmpty(), END_NOT_OURS, END_CLOSED) }

    private fun refused(): JsonReply =
        Refusals.reply(HttpStatusCode.Conflict, "the terminal did not take it", Refusal.REFUSED)

    /** Whether the client is gone: its process left, or, with no process known, its terminal closed. */
    private suspend fun exited(driving: SessionDriver, pane: SessionPane, record: SessionRecord): Boolean =
        record.process.pid?.let { handover.left(record.sessionId.orEmpty(), it, END_EXIT_SECONDS) }
            ?: !driving.terminal.isOpen(pane)
}
