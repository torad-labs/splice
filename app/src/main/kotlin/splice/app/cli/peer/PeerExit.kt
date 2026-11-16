// NEW: Oct 19, 2026, moved verbatim out of PeerCall.kt by concern (ConcentrationLawTest): what a `splice peer` call
// comes to, and the exit code each outcome leaves.
//
// Nov 15, 2026, from the second review of server mode's per-person limits: PeerAsk, what a verb does around its call,
// kept once. Presenting the management key, turning the answer into the printed line and the exit code, and spelling
// a name into a path were private to PeerCommand, so `splice server`'s verbs wrote them again. Both ask through it now.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonObject
import splice.core.terminal.TerminalOutput
import splice.core.wire.HttpStatus
import java.net.URLEncoder

/** The exit codes of `splice peer` (docs/specs/peer-messaging.md 5); 0, 1 and 2 keep their meaning across the CLI. */
internal object PeerExit {
    // why: success is 0 for every splice verb, so a script can test `splice peer send` like any command.
    const val DONE: Int = 0

    // why: 1 is the CLI's refusal: the daemon said no, or the key file could not be read.
    const val REFUSED: Int = 1

    // why: 2 is the CLI's usage error, as every other splice verb answers a bad argv.
    const val BAD_USAGE: Int = 2

    // why: spec 5, 3 is an unreachable daemon: none answered, or it stopped mid-call, and then the printed line says
    // the request may have run, so a send retried after it can arrive twice.
    const val UNREACHABLE: Int = 3

    // why: spec 5, 4 is the send rate's 429, so a caller backs off rather than giving up.
    const val RATE_LIMITED: Int = 4

    // why: spec 5, 5 is a 502 or 503: the helper or the target's socket failed, and a later try may pass.
    const val TRANSPORT: Int = 5

    /** The exit code of a call the daemon refused with [status]. */
    fun refused(status: Int): Int =
        when (status) {
            HttpStatus.TOO_MANY_REQUESTS -> RATE_LIMITED
            HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE -> TRANSPORT
            else -> REFUSED
        }
}

/** What a call came to: the daemon's 2xx body, or the exit code with the reason already printed. */
internal sealed class PeerAnswer {
    data class Ok(val body: JsonObject) : PeerAnswer()

    data class Exit(val code: Int) : PeerAnswer()
}

/** One call on the control plane; [body] goes up as JSON when there is one, and the answer is waited for [waitMs]. */
internal data class ControlRequest(
    val method: String,
    val path: String,
    val body: JsonObject? = null,
    val waitMs: Int = CALL_TIMEOUT_MS,
)

/** How a verb asks the daemon and prints what it answers. */
internal class PeerAsk(private val call: PeerCall, private val out: TerminalOutput) {
    /** Makes [asked] with the management key and prints its answer as [finish] does. With no key to present, the
     *  reason is already printed and the exit code is the CLI's refusal. */
    fun managed(asked: ControlRequest, json: Boolean = false, line: PeerLine): Int {
        val key = call.managementKey() ?: return PeerExit.REFUSED
        val answer = when (val body = asked.body) {
            null -> call.call(asked.method, asked.path, key, asked.waitMs)
            else -> call.call(asked.method, asked.path, key, body, asked.waitMs)
        }
        return finish(answer, json, line)
    }

    /** Prints a 2xx and answers 0: [line]'s text, or for [json] the daemon's whole answer on one line. Anything else
     *  is the exit code the call already chose, its reason already printed. */
    fun finish(answer: PeerAnswer, json: Boolean = false, line: PeerLine): Int = when (answer) {
        is PeerAnswer.Exit -> answer.code
        is PeerAnswer.Ok -> {
            val text = if (json) answer.body.toString() else line(answer.body)
            text?.let { out.line(it) }
            PeerExit.DONE
        }
    }

    /** [name] as it goes into a path or a query: an email's `@` and `+` arrive as they were typed. */
    fun segment(name: String): String = URLEncoder.encode(name, Charsets.UTF_8)
}
