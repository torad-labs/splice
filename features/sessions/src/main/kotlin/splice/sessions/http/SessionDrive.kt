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

internal const val NO_TERMINAL_TO_DRIVE = "splice has no terminal to drive sessions in yet"
internal const val STOP_NOT_OURS =
    "splice did not start this session, so it cannot stop it from here; stop it in the terminal it runs in"
internal const val ANSWER_NOT_OURS =
    "splice did not start this session, so it cannot answer it from here; answer it in the terminal it runs in"
private const val SAY_NOT_OURS =
    "splice did not start this session, so it cannot write to it from here; write in the terminal it runs in"
private const val SCREEN_NOT_OURS =
    "splice did not start this session, so it cannot read its screen; look at the terminal it runs in"
private const val DRAFT_HELD = "its prompt already holds words, and a message would be sent with them"
private const val TERMINAL_REFUSED = "the terminal did not take it: "
private const val LAUNCH_PANE_GONE =
    "the terminal this session was started in is gone; open it where it runs now"
private const val LAUNCH_PANE_TAKEN =
    "the terminal this session was started in is running something else now, so nothing is typed into it"

/** The console's hands on one session's terminal, by session id. A session has one when splice opened it (the pane
 *  memory) or when its own launch recorded the terminal it ran in, and only while that terminal still has it in front. */
public class SessionDrive(
    /** The terminal the drive reaches panes through; TeamStart opens a member's pane on the same one. */
    internal val driver: TerminalSource,
    private val choices: ScreenChoices = ScreenChoices(),
    private val launched: LaunchedTerminals = LaunchedTerminals { null },
) {
    /** The numbered choices a screen lists, in the order a person reads them. */
    private val choiceKeys = listOf(
        SessionKey.CHOICE_1, SessionKey.CHOICE_2, SessionKey.CHOICE_3, SessionKey.CHOICE_4, SessionKey.CHOICE_5,
        SessionKey.CHOICE_6, SessionKey.CHOICE_7, SessionKey.CHOICE_8, SessionKey.CHOICE_9,
    )

    /** Where the numbered keys begin, so a choice answers with the digit the person presses. */
    private val firstChoice = SessionKey.CHOICE_1.ordinal

    /** Give the session [text] as the person would: whole, line breaks intact, then submitted. The client takes it at
     *  once when idle and queues it mid-turn, which is its behaviour and not splice's to change. What is sent is
     *  exactly [text]: a prompt that already holds words is refused with them, and emptied first only when [clear]
     *  says so. */
    public fun say(session: String, text: String, clear: Boolean = false): JsonReply {
        if (text.isBlank()) return refuse(HttpStatusCode.BadRequest, "a message needs words in it")
        if (!clear) drafted(session)?.let { return it }
        return inPane(session, SAY_NOT_OURS, "its terminal is closed, so nothing can be sent to it") { driving, pane ->
            if (clear) driving.terminal.press(pane, SessionKey.CLEAR)
            driving.terminal.send(pane, text)
        }
    }

    /** The refusal a message meets when the session's prompt already holds words, carrying them; null when it is empty
     *  or splice cannot reach or read it, which the act itself then answers for. */
    private fun drafted(session: String): JsonReply? {
        val driving = driver() ?: return null
        val pane = locate(driving, session, SAY_NOT_OURS, "").pane?.takeIf(driving.terminal::isOpen) ?: return null
        val draft = Cancellables.runCatchingCleanup { choices.on(driving.terminal.screen(pane)).draft }.getOrDefault("")
        return draft.takeIf { it.isNotBlank() }?.let {
            Refusals.reply(HttpStatusCode.Conflict, DRAFT_HELD, Refusal.DRAFT, it)
        }
    }

    /** Answer what the session is waiting on by pressing its numbered choice, as the person would. */
    public fun answer(session: String, choice: Int): JsonReply {
        val key = choiceKeys.getOrNull(choice - 1)
            ?: return refuse(HttpStatusCode.BadRequest, "a choice is one of the numbered options, 1 to 9")
        return inPane(session, ANSWER_NOT_OURS, "its terminal is closed, so nothing is being asked") { driving, pane ->
            driving.terminal.press(pane, key)
        }
    }

    /** POST .../say's body, {"text": "...", "clear": true}: a body that names no text is refused as an empty message,
     *  and [say]'s clear is only ever the literal true. */
    public fun sayJson(session: String, body: String): JsonReply {
        val obj = JsonScalars.objectOrNull(Json, body)
        val clear = (obj?.get("clear") as? JsonPrimitive)?.booleanOrNull == true
        return say(session, obj?.let { JsonScalars.str(it, "text") }.orEmpty(), clear)
    }

    /** POST .../answer's body, {"choice": n}: a body that names none answers 0, which [answer] refuses in words. */
    public fun answerJson(session: String, body: String): JsonReply =
        answer(session, JsonScalars.objectOrNull(Json, body)?.let { JsonScalars.long(it, "choice") }?.toInt() ?: 0)

    /** Stop the turn the session is running, and nothing else. */
    public fun stop(session: String): JsonReply =
        inPane(session, STOP_NOT_OURS, "its terminal is closed, so no turn is running") { driving, pane ->
            driving.terminal.press(pane, SessionKey.STOP)
        }

    /** What the session's prompt asks right now, and the numbered choices its own client drew. A screen splice cannot
     *  read a choice on answers 200 with none, so a card keeps its fallback rather than failing over one pane. */
    public fun screen(session: String): JsonReply {
        val driving = driver() ?: return unwired()
        val at = locate(driving, session, SCREEN_NOT_OURS, "its terminal is closed, so nothing is being asked")
        val pane = at.pane ?: return requireNotNull(at.refusal)
        return Cancellables.runCatchingCleanup { driving.terminal.screen(pane) }.fold(
            onSuccess = { offered(session, choices.on(it)) },
            onFailure = { offered(session, ScreenOffer("", emptyList())) },
        )
    }

    private inline fun inPane(
        session: String,
        notOurs: String,
        closed: String,
        act: (SessionDriver, SessionPane) -> Unit,
    ): JsonReply {
        val driving = driver() ?: return unwired()
        val at = locate(driving, session, notOurs, closed)
        val pane = at.pane ?: return requireNotNull(at.refusal)
        return Cancellables.runCatchingCleanup { act(driving, pane) }.fold(
            onSuccess = { JsonReply(HttpStatusCode.OK, buildJsonObject { put("session_id", session) }.toString()) },
            onFailure = { refuse(HttpStatusCode.BadGateway, TERMINAL_REFUSED + SafeFailureText.render(it), REFUSED) },
        )
    }

    /** The pane to act in for [session], or the refusal that says why there is none. A pane splice opened is used
     *  while it is open. Otherwise the terminal the session's own launch recorded is used only while that pane is
     *  open AND still has the session's process in front, so a closed or reused terminal is refused by name. */
    private fun locate(driving: SessionDriver, session: String, notOurs: String, closed: String): Located {
        val mine = driving.panes.paneFor(session)
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

    private fun unwired() = refuse(HttpStatusCode.ServiceUnavailable, NO_TERMINAL_TO_DRIVE, Refusal.NO_TERMINAL)

    private fun refused(sentence: String, reason: Refusal) =
        Located(refusal = refuse(HttpStatusCode.Conflict, sentence, reason))

    /** Either the pane to act in or the refusal, never both. */
    private data class Located(val pane: SessionPane? = null, val refusal: JsonReply? = null)

    private fun offered(session: String, offer: ScreenOffer): JsonReply = JsonReply(
        HttpStatusCode.OK,
        buildJsonObject {
            put("session_id", session)
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
                            put("here", choice.here)
                        },
                    )
                }
            }
        }.toString(),
    )

    private fun refuse(status: HttpStatusCode, sentence: String, reason: Refusal? = null) =
        Refusals.reply(status, sentence, reason)
}
