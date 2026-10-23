// NEW: Oct 10, 2026 (BUILD.md first row, Sessions: chat right there and answer what it waits on) — driving ONE
// session from the console, by its own id.
//
//   POST /api/sessions/{id}/say      {"text": "..."}  give the session a message, whole, as the person would type it
//   POST /api/sessions/{id}/answer   {"choice": n}    press the numbered choice it is waiting on, 1 to 9
//   POST /api/sessions/{id}/stop                       stop the turn it is running, nothing else
//   GET  /api/sessions/{id}/screen                     what its prompt asks, and the choices its client drew
//
// ONE PLACE FOR THE PANE. A team member is a session too: TeamStart and TeamScreen find the slot's session and hand
// it here, so a member's card and a session's card drive the same pane with the same refusals.
//
// WHAT IS REFUSED, IN WORDS. A session splice did not open has no pane splice may type into (SessionPanes): it is
// refused with where to do it instead, never typed into blind. A pane the person closed is refused as closed. With
// no terminal wired at all, every act answers 503. The terminal is the contract's (SessionTerminal): no terminal
// product is named here.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.session.SessionKey
import splice.core.session.SessionPane
import splice.core.session.SessionTerminal
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.sessions.http.Refusal.CLOSED
import splice.sessions.http.Refusal.REFUSED
import splice.sessions.prompt.ScreenChoices
import splice.sessions.prompt.ScreenOffer
import java.util.concurrent.ConcurrentHashMap

internal const val NO_TERMINAL_TO_DRIVE = "splice has no terminal to drive sessions in yet"
internal const val STOP_NOT_OURS =
    "splice did not start this session, so it cannot stop it from here; stop it in the terminal it runs in"
internal const val ANSWER_NOT_OURS =
    "splice did not start this session, so it cannot answer it from here; answer it in the terminal it runs in"
private const val SAY_NOT_OURS =
    "splice did not start this session, so it cannot write to it from here; write in the terminal it runs in"
private const val SCREEN_NOT_OURS =
    "splice did not start this session, so it cannot read its screen; look at the terminal it runs in"
private const val ANSWER_CLOSED = "its terminal is closed, so nothing is being asked"
private const val SCREEN_MOVED = "the screen no longer shows that choice where it was, so nothing was pressed"
private const val DRAFT_HELD = "its prompt already holds words, and a message would be sent with them"
private const val DRAFT_UNREAD =
    "splice could not read its screen, so it cannot tell whether its prompt holds unsent words, and did nothing: "
private const val CHOICE_UNREAD =
    "splice could not read its screen, so it cannot see that choice is still there, and pressed nothing: "
private const val SAY_CLOSED = "its terminal is closed, so nothing can be sent to it"
private const val TERMINAL_REFUSED = "the terminal did not take it: "

// why: how many monitors the panes share, so two acts in one pane never interleave and a pane splice will not see
// again leaves nothing behind. Two panes that share a monitor only take turns.
private const val PANE_TURNS = 64
private const val LAUNCH_PANE_GONE =
    "the terminal this session was started in is gone; open it where it runs now"
private const val LAUNCH_PANE_TAKEN =
    "the terminal this session was started in is running something else now, so nothing is typed into it"
private const val PANE_CHANGED = "its terminal changed while this waited its turn, so nothing was typed into it"
private const val PANE_LEAVING = "it is ending, so nothing more is typed into its terminal"

/** A person acted on the session with this id: said something to it, answered it, or stopped it. */
public fun interface SessionActed {
    public fun acted(session: String)
}

/** The console's hands on one session's terminal, by session id. A session has one when splice opened it (the pane
 *  memory) or when its own launch recorded the terminal it ran in, and only while that terminal still has it in front. */
public class SessionDrive(
    /** The terminal the drive reaches panes through; TeamStart opens a member's pane on the same one. */
    internal val driver: TerminalSource,
    private val choices: ScreenChoices = ScreenChoices(),
    private val launched: LaunchedTerminals = LaunchedTerminals { null },
    /** Told of each act a person makes on a session, so what waits on that session knows it was handled. */
    private val acted: SessionActed = SessionActed {},
) {
    /** The numbered choices a screen lists, in the order a person reads them. */
    private val choiceKeys = listOf(
        SessionKey.CHOICE_1, SessionKey.CHOICE_2, SessionKey.CHOICE_3, SessionKey.CHOICE_4, SessionKey.CHOICE_5,
        SessionKey.CHOICE_6, SessionKey.CHOICE_7, SessionKey.CHOICE_8, SessionKey.CHOICE_9,
    )

    /** Where the numbered keys begin, so a choice answers with the digit the person presses. */
    private val firstChoice = SessionKey.CHOICE_1.ordinal

    /** The pane turns every act takes, End's included (SessionEnd). */
    internal val turns = PaneTurns()

    private val unwired = refuse(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_DRIVE, Refusal.NO_TERMINAL)

    /** Give the session [text] as the person would: whole, line breaks intact, then submitted. The client takes it at
     *  once when idle and queues it mid-turn, which is its behaviour and not splice's to change. What is sent is
     *  exactly [text]: a prompt that already holds words is refused with them, and emptied first only when [clear]
     *  says so. */
    public fun say(session: String, text: String, clear: Boolean = false): JsonReply {
        if (text.isBlank()) return refuse(HttpStatusCode.BadRequest, "a message needs words in it")
        return inPane(
            session,
            SAY_NOT_OURS,
            SAY_CLOSED,
            held = { driving, pane -> if (clear) null else draft(driving, pane) },
        ) { driving, pane ->
            if (clear) driving.terminal.press(pane, SessionKey.CLEAR)
            driving.terminal.send(pane, text)
        }
    }

    /** The refusal a message meets when the session's prompt already holds words, carrying them, or when its screen
     *  cannot be read, since a prompt splice could not see is not an empty one; null when it is empty or splice cannot
     *  reach it, which the act itself then answers for. SessionEnd and SessionContinue ask it before `/exit`. */
    internal fun drafted(session: String): JsonReply? {
        val driving = driver() ?: return null
        return locate(driving, session, SAY_NOT_OURS, "").pane?.let { draft(driving, it) }
    }

    /** What [pane]'s prompt holds, as the refusal [drafted] answers. */
    private fun draft(driving: SessionDriver, pane: SessionPane): JsonReply? =
        Cancellables.runCatchingCleanup { choices.on(driving.terminal.screen(pane)).draft }.fold(
            onSuccess = { held ->
                held.takeIf { it.isNotBlank() }
                    ?.let { Refusals.reply(HttpStatusCode.Conflict, DRAFT_HELD, Refusal.DRAFT, it) }
            },
            onFailure = { refuse(HttpStatusCode.Conflict, DRAFT_UNREAD + SafeFailureText.render(it), REFUSED) },
        )

    /** Answer what the session is waiting on as the person would: a numbered choice by its digit, and a menu drawn
     *  without numbers (the folder trust question) by moving the pointer from where the screen shows it, then
     *  accepting. [label], when the page sends the words it showed, must still be that choice's words on the screen:
     *  a screen that changed under the click is refused, never answered with whatever now sits at that place, and a
     *  screen splice could not read is refused too, since nothing on it can be said to be where it was. The screen
     *  is read inside the pane's turn, so the keys pressed are the ones the screen showed when they went. */
    public fun answer(session: String, choice: Int, label: String? = null): JsonReply {
        driver() ?: return unwired
        val key = choiceKeys.getOrNull(choice - 1)
            ?: return refuse(HttpStatusCode.BadRequest, "a choice is one of the numbered options, 1 to 9")
        var offer: ScreenOffer? = null
        return inPane(
            session,
            ANSWER_NOT_OURS,
            ANSWER_CLOSED,
            held = { driving, pane ->
                val read = Cancellables.runCatchingCleanup { choices.on(driving.terminal.screen(pane)) }
                offer = read.getOrNull()
                val unread = read.exceptionOrNull()
                when {
                    unread != null ->
                        refuse(HttpStatusCode.Conflict, CHOICE_UNREAD + SafeFailureText.render(unread), REFUSED)
                    offer?.moved(choice, label) == true ->
                        refuse(HttpStatusCode.Conflict, SCREEN_MOVED, Refusal.SCREEN_MOVED)
                    else -> null
                }
            },
        ) { driving, at -> (offer?.keys(choice, key) ?: listOf(key)).forEach { driving.terminal.press(at, it) } }
    }

    /** POST .../say's body, {"text": "...", "clear": true}: a body that names no text is refused as an empty message,
     *  and [say]'s clear is only ever the literal true. */
    public fun sayJson(session: String, body: String): JsonReply {
        val obj = JsonScalars.objectOrNull(Json, body)
        val clear = (obj?.get("clear") as? JsonPrimitive)?.booleanOrNull == true
        return say(session, obj?.let { JsonScalars.str(it, "text") }.orEmpty(), clear)
    }

    /** POST .../answer's body, {"choice": n, "label": "..."}: a body that names none answers 0, which [answer] refuses
     *  in words; the label is optional and, when sent, is checked against the screen. */
    public fun answerJson(session: String, body: String): JsonReply {
        val obj = JsonScalars.objectOrNull(Json, body)
        return answer(
            session,
            obj?.let {
                JsonScalars.long(it, "choice")
            }?.toInt() ?: 0,
            obj?.let { JsonScalars.str(it, "label") },
        )
    }

    /** Stop the turn the session is running, and nothing else. */
    public fun stop(session: String): JsonReply =
        inPane(session, STOP_NOT_OURS, "its terminal is closed, so no turn is running") { driving, pane ->
            driving.terminal.press(pane, SessionKey.STOP)
        }

    /** What the session's prompt asks right now, and the numbered choices its own client drew. A screen splice cannot
     *  read a choice on answers 200 with none, so a card keeps its fallback rather than failing over one pane. */
    public fun screen(session: String): JsonReply {
        val driving = driver() ?: return unwired
        val at = locate(driving, session, SCREEN_NOT_OURS, "its terminal is closed, so nothing is being asked")
        val pane = at.pane ?: return requireNotNull(at.refusal)
        return Cancellables.runCatchingCleanup { driving.terminal.screen(pane) }.fold(
            onSuccess = { offered(choices.on(it)) },
            onFailure = { offered(ScreenOffer("", emptyList())) },
        )
    }

    /** [act] in the session's pane, holding that pane's turn from [held], which refuses the act once the pane is held
     *  (null lets it go on), to the act's last key. */
    private inline fun inPane(
        session: String,
        notOurs: String,
        closed: String,
        crossinline held: (SessionDriver, SessionPane) -> JsonReply? = { _, _ -> null },
        crossinline act: (SessionDriver, SessionPane) -> Unit,
    ): JsonReply {
        val driving = driver() ?: return unwired
        val at = locate(driving, session, notOurs, closed)
        val pane = at.pane ?: return requireNotNull(at.refusal)
        return turns.turn(pane, session, { locate(driving, session, notOurs, closed) }) {
            held(driving, pane) ?: run {
                acted.acted(session)
                Cancellables.runCatchingCleanup { act(driving, pane) }.exceptionOrNull()?.let {
                    refuse(HttpStatusCode.BadGateway, TERMINAL_REFUSED + SafeFailureText.render(it), REFUSED)
                }
            }
        } ?: JsonReply(HttpStatusCode.OK, "{}")
    }

    /** The pane to act in for [session], or the refusal that says why there is none. A pane splice opened is used
     *  while it is open, which the terminal answers for the very pane it opened: the process it started there, not
     *  only the pane's number, which a restarted server hands to another shell. A remembered pane that carries no
     *  such process (one an earlier build wrote, or a shell a move resumed in) is never trusted on its number: it is
     *  found afresh, as a session splice remembers no pane for. Otherwise the terminal the session's own launch
     *  recorded is used only while that pane is open AND still has the session's process in front, so a closed or
     *  reused terminal is refused by name. */
    internal fun locate(driving: SessionDriver, session: String, notOurs: String, closed: String): Located {
        val mine = driving.panes.paneFor(session)?.takeIf(driving.terminal::owned)
        if (mine != null) return if (driving.terminal.isOpen(mine)) Located(pane = mine) else refused(closed, CLOSED)
        val launch = launched.of(session) ?: return refused(notOurs, Refusal.NOT_OURS)
        return launchedPane(driving.terminal, launch)
    }

    /** The pane a launch recorded, while it is open and still has that launch's process in front. */
    private fun launchedPane(terminal: SessionTerminal, launch: LaunchedTerminal): Located {
        val pane = terminal.recorded(launch.pane, launch.server)?.takeIf(terminal::isOpen)
        return when {
            pane == null -> refused(LAUNCH_PANE_GONE, Refusal.PANE_GONE)
            !terminal.hosts(pane, launch.pid) -> refused(LAUNCH_PANE_TAKEN, Refusal.PANE_TAKEN)
            else -> Located(pane = pane)
        }
    }

    private fun refused(sentence: String, reason: Refusal) =
        Located(refusal = refuse(HttpStatusCode.Conflict, sentence, reason))

    /** Either the pane to act in or the refusal, never both. SessionContinue reads it to end a client in its pane. */
    internal data class Located(val pane: SessionPane? = null, val refusal: JsonReply? = null)

    private fun offered(offer: ScreenOffer): JsonReply = JsonReply(
        HttpStatusCode.OK,
        buildJsonObject {
            put("asked", offer.asked)
            put("draft", offer.draft)
            // The whole prompt above the choices: the tool, what it runs (framed) and why, read before answering.
            putJsonArray("panel") {
                offer.panel.forEach { line ->
                    add(
                        buildJsonObject {
                            put("text", line.text)
                            if (line.framed) put("framed", true)
                        },
                    )
                }
            }
            putJsonArray("choices") {
                offer.choices.forEach { choice ->
                    add(
                        buildJsonObject {
                            // The digit the person presses, which is the same number POST .../answer takes.
                            put("choice", choice.key.ordinal - firstChoice + 1)
                            put("label", choice.label)
                        },
                    )
                }
            }
        }.toString(),
    )

    private fun refuse(status: HttpStatusCode, sentence: String, reason: Refusal? = null) =
        Refusals.reply(status, sentence, reason)
}

/** One act at a time in a pane, from what it checks on the screen to its last key: two messages at once would each
 *  find the prompt empty, and one's paste or clear would land between the other's paste and its Enter. Say, answer,
 *  stop, End and Continue on all take a pane's turn here, and once `/exit` is typed into a pane for a session, nothing
 *  but that session's own End or move is typed there until its client is seen gone. */
internal class PaneTurns(
    /** Whether a client process still runs: the operating system's answer, read for a pane whose exit wait ran out. */
    private val alive: ClientAlive =
        ClientAlive { pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) },
) {
    /** Each monitor serializes the terminal calls it wraps and guards no state. */
    private val monitors = List(PANE_TURNS) { Any() }

    /** Each pane `/exit` was typed into, with the session and client process it was typed for: what lands after the
     *  client goes lands in whatever it leaves in front, a shell that would run it. The mark goes once the client is
     *  seen leaving. A wait that ran out or was cancelled keeps it, since the client may still be going (PR #247's
     *  fourth review), until a later act finds that process gone, the way a client resumed in the same pane is
     *  reached again (fifth review). A mark with no process known stays until an End or a move sees it leave. */
    private val leaving = ConcurrentHashMap<String, Leaver>()

    /** How many acts each session has been given, so an End or a move decided on an earlier look sees one land. */
    private val acts = ConcurrentHashMap<String, Long>()

    /** How many acts [session] has been given: End and Continue on read it as the request arrives, before anything
     *  reads the session, and again in their turn. */
    fun acts(session: String): Long = acts[session] ?: 0L

    /** [act] for [session] in [pane] holding its turn, once [again] (the session located anew inside the turn) still
     *  finds it there and no `/exit` was typed there for it: a client that exited, or a pane that came to hold another
     *  process, while this waited is refused, never typed into. Null when [act] went through, which counts as an
     *  act. */
    fun turn(pane: SessionPane, session: String, again: Relocate, act: InTurn): JsonReply? =
        held(pane, session, again, ending = false, act)

    /** End's and Continue on's own keys before `/exit` (the Escape that stops a turn): the same turn, which a session
     *  being ended may take again, and not counted as an act on it. */
    fun ending(pane: SessionPane, session: String, again: Relocate, act: InTurn): JsonReply? =
        held(pane, session, again, ending = true, act)

    /** [exit]'s check and keys in [pane]'s turn, then its wait for the client to leave. From the keys on, [pane] is
     *  marked as being left by [session]'s [Exit.pid] until the client is seen gone; a terminal that refused the keys
     *  may still have taken them, so it keeps the mark too. Null once the client has left; else the refusal, or
     *  [Exit.stayed]. */
    suspend fun leave(pane: SessionPane, session: String, again: Relocate, exit: Exit): JsonReply? {
        val mark = Leaver(session, exit.pid)
        held(pane, session, again, ending = true) {
            exit.check() ?: run {
                leaving[pane.id] = mark
                exit.keys()
            }
        }?.let { return it }
        if (!exit.left()) return exit.stayed()
        leaving.remove(pane.id, mark)
        return null
    }

    private fun held(pane: SessionPane, session: String, again: Relocate, ending: Boolean, act: InTurn): JsonReply? =
        synchronized(monitors[Math.floorMod(pane.id.hashCode(), PANE_TURNS)]) {
            val now = again()
            when {
                now.pane == null -> requireNotNull(now.refusal)
                now.pane != pane -> Refusals.reply(HttpStatusCode.Conflict, PANE_CHANGED, Refusal.PANE_TAKEN)
                !ending && stillLeaving(pane, session) ->
                    Refusals.reply(HttpStatusCode.Conflict, PANE_LEAVING, Refusal.ENDED)
                else -> act().also { refused -> if (refused == null && !ending) acts.merge(session, 1L, Long::plus) }
            }
        }

    /** Whether [pane] is still being left by [session]'s client: marked for it, and that client's process, when
     *  known, still running. A mark whose process has gone is dropped here, in the pane's turn. */
    private fun stillLeaving(pane: SessionPane, session: String): Boolean {
        val mark = leaving[pane.id]?.takeIf { it.session == session } ?: return false
        val gone = mark.pid?.let { !alive(it) } ?: false
        if (gone) leaving.remove(pane.id, mark)
        return !gone
    }

    /** A pane's `/exit` mark: the session it was typed for, and its client's process when the record knew one. */
    private data class Leaver(val session: String, val pid: Long?)

    /** The session located anew inside its pane's turn, as SessionDrive.locate answers it. */
    fun interface Relocate {
        operator fun invoke(): SessionDrive.Located
    }

    /** What an act types once its pane's turn is held: the refusal that stopped it, or null once its keys went. */
    fun interface InTurn {
        operator fun invoke(): JsonReply?
    }

    /** Whether the client process [pid] still runs. */
    fun interface ClientAlive {
        operator fun invoke(pid: Long): Boolean
    }

    /** What End and Continue on type to end a client, in the order [leave] runs it. */
    interface Exit {
        /** The client process `/exit` is typed to, when the session's record knows it; null keeps the pane marked
         *  until an End or a move sees the client leave. */
        val pid: Long?

        /** The refusal that keeps the keys from going, read inside the turn; null lets them go. */
        fun check(): JsonReply?

        /** `/exit` and what empties the prompt before it; the refusal when the terminal did not take them. */
        fun keys(): JsonReply?

        /** Whether the client left the pane within the route's wait. */
        suspend fun left(): Boolean

        /** The answer when it did not. */
        fun stayed(): JsonReply
    }
}
