// NEW: server mode (docs/specs/server-mode.md). How the control plane's server routes find the daemon's server mode
// and say its answers: JSON as JSON, a text body such as a CSV report under its own type, and every refusal in the
// control plane's error shape. A daemon with no server mode answers 409, saying why it has none.
package splice.app.control.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import splice.app.control.ConsolePorts
import splice.app.control.api.ControlBodies
import splice.http.JsonReply
import splice.server.ServerAnswer
import splice.server.ServerMode

internal class ServerModeReplies(private val ports: ConsolePorts) {
    /** The running server mode, or null with the 409 answered. */
    suspend fun running(call: ApplicationCall): ServerMode? {
        val source = ports.serverMode
        val mode = source?.mode()
        if (mode == null) refuse(call, HttpStatusCode.Conflict, source?.absent() ?: NO_SERVER_SOURCE)
        return mode
    }

    suspend fun say(call: ApplicationCall, answer: ServerAnswer) {
        when (answer) {
            is ServerAnswer.Done -> JsonReply(answer.status, answer.body.toString()).send(call)
            is ServerAnswer.Refused -> refuse(call, answer.status, answer.why)
            is ServerAnswer.Text -> call.respondText(answer.text, ContentType.parse(answer.contentType), answer.status)
        }
    }

    private suspend fun refuse(call: ApplicationCall, status: HttpStatusCode, why: String) {
        JsonReply(status, ControlBodies.errorJson(why)).send(call)
    }
}

// What a control server answers when no daemon handed it a server mode to ask, as in a test that builds one bare.
private const val NO_SERVER_SOURCE = "server mode is not available in this build"
