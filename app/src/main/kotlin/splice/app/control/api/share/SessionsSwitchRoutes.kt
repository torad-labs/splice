// NEW: LAN share, phase 2, the round-1 review (docs/specs/lan-share.md, Bounds). The Sessions switch on each machine
// card of the Sharing page:
//
//   PUT /api/share/machines/{name}/sessions   {on}: whether a machine that joined this one may cross sessions
//   PUT /api/remotes/{name}/sessions          {on}: whether a machine this one joined may cross sessions
//
// A switch starts off, and a re-join turns it off again (the President's decision on the round-1 review, Oct 23): no
// joined machine lists, writes into or reads from this machine's sessions until Marcos turns it on. A refusal is an
// answer, as the page's other rows give one (Said).
package splice.app.control.api.share

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.SharingParts
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.share.ShareState
import splice.share.remote.RemotesStore
import splice.upstream.codemode.ProcessDispatchers

internal class SessionsSwitchRoutes(private val wired: SharingWired) {
    private val io = ProcessDispatchers().io()
    private val json = Json { ignoreUnknownKeys = true }

    /** Turns the Sessions switch of [name], a machine that joined this one, as the body says. */
    suspend fun machine(name: String, call: ApplicationCall): JsonReply =
        switched(call, "no machine named '$name' has joined") { parts, on ->
            parts.listener.clients.allowSessions(name, on)
        }

    /** Turns the Sessions switch of [name], a machine this one joined, where the daemon's link reads it. */
    suspend fun remote(name: String, call: ApplicationCall): JsonReply =
        switched(call, "this machine has not joined one named '$name'") { parts, on ->
            RemotesStore(ShareState(StatePaths(envReader = parts.env)).remotesFile).allowSessions(name, on)
        }

    /** One switch turned by [turn], which answers false for a machine it does not know, said as [unknown]. */
    private suspend fun switched(call: ApplicationCall, unknown: String, turn: SessionsTurn): JsonReply {
        val text = call.receiveText()
        val change = Cancellables.runCatchingCancellable { json.decodeFromString(SessionsSwitch.serializer(), text) }
            .fold(onSuccess = { it }, onFailure = { null })
            ?: return JsonReply(HttpStatusCode.BadRequest, error("the body must be $SESSIONS_BODY"))
        val parts = wired.parts() ?: return JsonReply(HttpStatusCode.ServiceUnavailable, error(SHARING_UNWIRED))
        return withContext(io) {
            val said = Said()
            val found = Cancellables.runCatchingCancellable { turn.turned(parts, change.on) }.getOrElse { failure ->
                said.bad("the switch was not turned: ${SafeFailureText.render(failure)}")
                return@withContext said.refused()
            }
            if (!found) said.bad(unknown)
            said.answer(ok = found, restart = false)
        }
    }

    private fun error(message: String): String = buildJsonObject { put("error", message) }.toString()
}

/** One book's Sessions switch: turned [on] or off for the machine the route names, false when the book has none. */
private fun interface SessionsTurn {
    fun turned(parts: SharingParts, on: Boolean): Boolean
}

/** What a PUT of a Sessions switch sends. */
@Serializable
private data class SessionsSwitch(val on: Boolean)

// why: what a PUT of a Sessions switch that the route cannot read is told it must send.
private const val SESSIONS_BODY = "{\"on\": true|false}"
