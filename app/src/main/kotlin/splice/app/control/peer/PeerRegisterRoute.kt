// NEW: Oct 28, 2026, out of PeersMount.kt by concern (ConcentrationLawTest), with the repeat register (Marcos: "add the
// ability for anyone or anything to register to send and receive messages via splice peer"): POST /api/peers.
package splice.app.control.peer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.UserHome
import splice.core.util.LogSafe
import splice.http.JsonBody
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.store.PeerRefusal
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.RegisterOutcome
import splice.sessions.registry.SessionAvailability

/** POST /api/peers (spec 3.2): 201 for a new peer, 200 for a register that repeats one already there. */
internal class PeerRegisterRoute(private val parts: PeerParts) {
    private val store = parts.store
    private val jsonBody = JsonBody()
    private val owners = PeerOwnerInputs()

    /** POST /api/peers: writes peer.json and peer.key, starts the helper, and answers once its record is on disk. */
    suspend fun register(call: ApplicationCall) {
        val body = jsonBody.parse(call)
        val answer = when (val owner = owners.parse(body)) {
            PeerOwnerInput.Persistent -> registered(body, null)
            is PeerOwnerInput.Owned -> registered(body, owner.process)
            PeerOwnerInput.Invalid -> PeerReplies.refuse(
                HttpStatusCode.BadRequest,
                "owner must name a live pid with its exact procStart and pidDomain",
            )
        }
        answer.send(call)
    }

    private suspend fun registered(body: JsonObject?, owner: NativeProcess?): PeerAnswer {
        val name = PeerReplies.string(body, "name")
        val cwd = if (body?.containsKey("cwd") == true) PeerReplies.string(body, "cwd") else UserHome.dir().toString()
        val sendTo = sendTo(body)
        if (name == null) return PeerReplies.refuse(HttpStatusCode.BadRequest, "body must be {\"name\": \"<name>\"}")
        if (cwd == null || sendTo == null) {
            return PeerReplies.refuse(HttpStatusCode.BadRequest, "cwd is a path and sendTo a list of names")
        }
        val live = parts.registry.read().filter { it.availability != SessionAvailability.GONE }
        val liveNames = live.mapNotNull { it.name }.toSet()
        return when (val outcome = store.register(PeerRequest(name, cwd, sendTo, owner), liveNames)) {
            is PeerRegistration.Refused -> PeerReplies.refuse(
                if (outcome.why == PeerRefusal.INVALID) HttpStatusCode.BadRequest else HttpStatusCode.Conflict,
                outcome.reason,
            )
            is PeerRegistration.Registered -> answered(name, outcome)
        }
    }

    /** The answer to a register that went through: 201 for a new peer, 200 for one already there. */
    private suspend fun answered(name: String, outcome: PeerRegistration.Registered): PeerAnswer {
        // A register that changed a peer already there restarts its helper on the new cwd, scope and owner.
        if (outcome.outcome == RegisterOutcome.UPDATED) parts.helpers?.stop(name)
        val pid = parts.helpers?.start(name)
        val created = outcome.outcome == RegisterOutcome.CREATED
        if (created) {
            parts.log("[peers] registered ${LogSafe.str(name)}\n")
        } else {
            parts.log("[peers] registered ${LogSafe.str(name)} again\n")
        }
        return PeerReplies.ok(
            if (created) HttpStatusCode.Created else HttpStatusCode.OK,
            buildJsonObject {
                put("name", name)
                put("sessionId", outcome.config.sessionId)
                put("keyFile", outcome.keyFile.toString())
                put("socket", parts.helpers?.running(name)?.socket)
                put("pid", pid)
            },
        )
    }

    /** `sendTo` as given, or `["*"]` when absent; null when it is there but not a list of strings. */
    private fun sendTo(body: JsonObject?): List<String>? {
        val raw = body?.get("sendTo") ?: return listOf("*")
        val items = (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return items?.takeIf { list -> list.none { it == null } }?.filterNotNull()
    }
}
