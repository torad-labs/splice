// NEW: door-default (docs/specs/door-default.md, decision 5). What `splice claude door`, `splice claude head` and
// `splice claude` do about splice's /login, the one file splice lays in the person's Claude folder (DoorLogin), said
// in words beside the lane's own sentence. A disk that refuses changes no lane: it is said, and the move still holds.
package splice.app.desktop.lane

import splice.client.login.DoorLogin
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.nio.file.Path

internal class ClaudeLogin(private val login: DoorLogin, private val log: LogSink) {
    /** splice's /login laid, for `splice claude door`: what to say beside the move, or null when it is in place. */
    fun laid(): String? = Cancellables.runCatchingCancellable { login.lay() }.fold(
        onSuccess = { theirs -> theirs?.let(::inTheWay) },
        onFailure = { refused("laid", it) },
    )

    /** splice's /login taken out, for `splice claude head`, and only splice's: what to say beside the move, or null. */
    fun removed(): String? = Cancellables.runCatchingCancellable { login.remove() }.fold(
        onSuccess = { null },
        onFailure = { refused("taken out", it) },
    )

    /** The person's own /login, where it keeps splice's out of the sessions on the door; null when there is none. */
    fun theirs(): Path? = Cancellables.runCatchingCancellable { login.theirs() }
        .onFailure { log("[claude] no /login was read in the person's Claude folder: ${SafeFailureText.render(it)}\n") }
        .getOrNull()

    private fun inTheWay(theirs: Path): String =
        "your own /login at $theirs is in the way, so it is left as it is and /login in a session is yours, not " +
            "splice's accounts (`splice console` opens them)"

    private fun refused(what: String, failure: Throwable): String {
        val why = SafeFailureText.render(failure)
        log("[claude] splice's /login was not $what: $why\n")
        return "splice's /login was not $what ($why)"
    }
}
