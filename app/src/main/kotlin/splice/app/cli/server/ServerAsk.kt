// NEW: server mode (docs/specs/server-mode.md, F1 and F3). The one way a `splice server` verb asks the daemon: a call on
// the control plane with the management key, and its answer printed the same way whichever verb asked.
package splice.app.cli.server

import kotlinx.serialization.json.JsonObject
import splice.app.cli.peer.PeerAnswer
import splice.app.cli.peer.PeerCall
import splice.app.cli.peer.PeerExit
import splice.app.cli.peer.PeerLine
import splice.core.terminal.TerminalOutput
import java.net.URLEncoder

/** One call on the control plane; [body] goes up as JSON when there is one. */
internal data class ServerCall(val method: String, val path: String, val body: JsonObject? = null)

internal class ServerAsk(private val call: PeerCall, private val out: TerminalOutput) {
    /** Makes [asked] with the management key. A 2xx's body is printed, whole for [json] and as [line] reads it
     *  otherwise, and the answer is 0; anything else is the exit code the call chose, its reason already printed. */
    fun send(asked: ServerCall, line: PeerLine, json: Boolean = false): Int {
        val key = call.managementKey() ?: return PeerExit.REFUSED
        val answer = when (val body = asked.body) {
            null -> call.call(asked.method, asked.path, key)
            else -> call.call(asked.method, asked.path, key, body)
        }
        return when (answer) {
            is PeerAnswer.Exit -> answer.code
            is PeerAnswer.Ok -> {
                out.line(if (json) answer.body.toString() else line(answer.body).orEmpty())
                PeerExit.DONE
            }
        }
    }

    /** [word] as it goes into a path or a query: an email's `@` and `+` arrive as they were typed. */
    fun segment(word: String): String = URLEncoder.encode(word, Charsets.UTF_8)
}
