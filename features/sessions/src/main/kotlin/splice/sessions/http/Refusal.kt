// NEW: Oct 10, 2026 — why an act on a session or a team member was refused, as a key a page names in a word.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.http.JsonReply

/** Why an act on a session was refused, as the stable key a refusal body carries beside its sentence. */
internal enum class Refusal(val key: String) {
    /** The member holds no session, so nothing runs to act on: its card's own state says so. */
    ENDED("ended"),

    /** The terminal splice opened for it is closed, so nothing runs there. */
    CLOSED("closed"),

    /** splice did not start it and no launch recorded its terminal. */
    NOT_OURS("not_ours"),

    /** The terminal its launch recorded is closed. */
    PANE_GONE("pane_gone"),

    /** The terminal its launch recorded runs something else now. */
    PANE_TAKEN("pane_taken"),

    /** splice has no terminal wired to drive sessions in. */
    NO_TERMINAL("no_terminal"),

    /** The terminal did not take the keys. */
    REFUSED("refused"),

    /** The prompt already holds words, which a message would be typed after: shown to the person, cleared only on
     *  his say-so (Marlin, Oct 10). */
    DRAFT("draft"),

    /** The screen no longer shows the choice the page drew under the click, so nothing was pressed. */
    SCREEN_MOVED("screen_moved"),

    /** A turn is running, which ending the session would cut: shown to the person, ended only on his say-so (Marlin). */
    WORKING("working"),

    /** The manager (ManagerSession): no head named, or none splice runs by that key. */
    HEAD_UNKNOWN("head_unknown"),

    /** The manager's head has no command linked where `splice install` puts it. */
    NOT_INSTALLED("not_installed"),

    /** The manager's terminal did not open, or Claude Code did not register in it in time. */
    LAUNCH_FAILED("launch_failed"),

    /** The manager's folder could not be made ready. */
    FOLDER_MISSING("folder_missing"),

    /** The manager already runs on the head asked for. */
    SAME_HEAD("same_head"),

    /** The manager waits on an answer, which a move would type `/exit` into. */
    WAITING("waiting"),

    /** It holds a feedback draft the person has not sent, which its `/exit` would ask them to send or discard: theirs
     *  to do, so nothing is typed into it (Oct 27, 2026). */
    FEEDBACK("feedback"),
}

/** A refusal's reply: its sentence, for the log, and its [Refusal] key when it has one, which is what a page names
 *  it by (fin). A page never matches the sentence. */
internal object Refusals {
    fun reply(status: HttpStatusCode, sentence: String, reason: Refusal? = null, draft: String? = null): JsonReply =
        JsonReply(
            status,
            buildJsonObject {
                put("error", sentence)
                reason?.let { put("reason", it.key) }
                draft?.let { put("draft", it) }
            }.toString(),
        )
}
