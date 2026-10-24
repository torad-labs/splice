// NEW: LAN share, phase 2, the round-1 review (docs/specs/lan-share.md, Bounds). The Sessions switch on each machine
// card of the Sharing page:
//
//   PUT /api/share/machines/{name}/sessions   {on, joined_at}: whether a machine that joined this one may cross sessions
//   PUT /api/remotes/{name}/sessions          {on}: whether a machine this one joined may cross sessions
//
// A switch starts off, and a re-join turns it off again (the President's decision on the round-1 review, Oct 23): no
// joined machine lists, writes into or reads from this machine's sessions until Marcos turns it on. Each route turns its
// own card's book alone (SessionsSwitch): anyone on the network may join under the name of a machine this one joined,
// and only the card that shows where a machine joined from lets it in. A machine on both lists crosses sessions while
// both its cards are on. A refusal is an answer, as the page's other rows give one (Said). A card of a machine that
// joined this one names the join it shows (round 14 of PR #419's review): a page drawn before that machine was revoked
// and another joined under its name turns nothing of the one that joined since.
package splice.app.control.api.share

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.control.SharingParts
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply
import splice.upstream.codemode.ProcessDispatchers

internal class SessionsSwitchRoutes(private val wired: SharingWired) {
    private val io = ProcessDispatchers().io()
    private val json = Json { ignoreUnknownKeys = true }

    /** Turns the Sessions switch of [name], a machine that joined this one, as the body says, while the machine of that
     *  name is still the join the card showed. */
    suspend fun machine(name: String, call: ApplicationCall): JsonReply = switched(call) { parts, change ->
        val switch = parts.listener.switch
        when {
            change.joinedAt == null -> "the body must name the join its card shows: $SESSIONS_JOINED_BODY"
            switch.turnJoined(name, change.on, change.joinedAt) -> null
            switch.joined(name) -> "'$name' joined again since this page was drawn; its switch is as it was"
            else -> "no machine named '$name' has joined"
        }
    }

    /** Turns the Sessions switch of [name], a machine this one joined, where the daemon's link reads it. */
    suspend fun remote(name: String, call: ApplicationCall): JsonReply = switched(call) { parts, change ->
        "this machine has not joined one named '$name'".takeUnless { parts.listener.switch.turnUsed(name, change.on) }
    }

    /** One switch turned by [turn], which answers why it turned nothing, or null when it turned it. */
    private suspend fun switched(call: ApplicationCall, turn: SessionsTurn): JsonReply {
        val text = call.receiveText()
        val change = Cancellables.runCatchingCancellable { json.decodeFromString(SessionsBody.serializer(), text) }
            .fold(onSuccess = { it }, onFailure = { null })
            ?: return JsonReply(HttpStatusCode.BadRequest, error("the body must be $SESSIONS_BODY"))
        val parts = wired.parts() ?: return JsonReply(HttpStatusCode.ServiceUnavailable, error(SHARING_UNWIRED))
        return withContext(io) {
            val said = Said()
            val refused = Cancellables.runCatchingCancellable { turn.turned(parts, change) }.getOrElse { failure ->
                said.bad("the switch was not turned: ${SafeFailureText.render(failure)}")
                return@withContext said.refused()
            }
            refused?.let(said::bad)
            said.answer(ok = refused == null, restart = false)
        }
    }

    private fun error(message: String): String = buildJsonObject { put("error", message) }.toString()
}

/** One book's Sessions switch, turned as [change] says for the machine the route names: null when it was, or why not. */
private fun interface SessionsTurn {
    fun turned(parts: SharingParts, change: SessionsBody): String?
}

/** What a PUT of a Sessions switch sends: on or off, and for a machine that joined this one, the join its card shows. */
@Serializable
private data class SessionsBody(val on: Boolean, @SerialName("joined_at") val joinedAt: String? = null)

// why: what a PUT of a Sessions switch that the route cannot read is told it must send.
private const val SESSIONS_BODY = "{\"on\": true|false}"

// why: what a PUT of the switch of a machine that joined this one, naming no join, is told it must send.
private const val SESSIONS_JOINED_BODY = "{\"on\": true|false, \"joined_at\": \"<when it joined, as its card shows>\"}"
