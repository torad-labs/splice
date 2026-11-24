// NEW: Oct 28, 2026, out of PeersMount.kt by concern (ConcentrationLawTest), with the repeat register (anyone or anything
// can register to send and receive messages via splice peer): POST /api/peers.
package splice.app.control.peer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.UserHome
import splice.core.harness.HarnessPeerConfig
import splice.core.harness.HarnessPeerProtocol
import splice.core.mcp.McpHarness
import splice.core.util.LogSafe
import splice.http.JsonBody
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses
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
    private val processes = PeerOwnerProcesses()

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

    suspend fun subscribeClaude(pid: Long, cwd: String): Boolean {
        val harness = McpHarness.CLAUDE.definition
        if (harness.peer?.autoSubscribe != true) return false
        val owner = processes.capture(pid) ?: return false
        val registered = registerValid(PeerRequest("cc-$pid", cwd, listOf("*"), owner, true, harness.wire), owner)
        return registered.status == HttpStatusCode.Created || registered.status == HttpStatusCode.OK
    }

    private suspend fun registered(body: JsonObject?, owner: NativeProcess?): PeerAnswer =
        when (val parsed = parseRequest(body, owner)) {
            is ParsedRequest.Bad -> PeerReplies.refuse(HttpStatusCode.BadRequest, parsed.reason)
            is ParsedRequest.Valid -> registerValid(parsed.request, owner)
        }

    private suspend fun registerValid(request: PeerRequest, owner: NativeProcess?): PeerAnswer {
        val live = parts.registry.read().filter { it.availability != SessionAvailability.GONE }
        val liveNames = live.mapNotNull { it.name }.toSet()
        return when (val outcome = store.register(request, liveNames)) {
            is PeerRegistration.Refused -> PeerReplies.refuse(
                if (outcome.why == PeerRefusal.INVALID) HttpStatusCode.BadRequest else HttpStatusCode.Conflict,
                outcome.reason,
            )
            is PeerRegistration.Registered -> answered(request.name, outcome, owner)
        }
    }

    private fun parseRequest(body: JsonObject?, owner: NativeProcess?): ParsedRequest {
        val name = PeerReplies.string(body, "name")
            ?: return ParsedRequest.Bad("""body must be {"name": "<name>"}""")
        val cwd = if (body?.containsKey("cwd") == true) PeerReplies.string(body, "cwd") else UserHome.dir().toString()
        val sendTo = sendTo(body)
        if (cwd == null || sendTo == null) return ParsedRequest.Bad("cwd is a path and sendTo a list of names")
        return bind(body, PeerRequest(name, cwd, sendTo, owner))
    }

    private fun bind(body: JsonObject?, request: PeerRequest): ParsedRequest {
        val harness = PeerReplies.string(body, "harness")
        val peer = parts.harnesses.entries.find { it.wire == harness }?.peer
        val driver = peer?.driver
        val nativeInbox = nativeInbox(body, driver)
        return when {
            body?.containsKey("harness") == true && !autoSubscription(request.owner, peer) ->
                ParsedRequest.Bad("harness requires a live owner and an enabled auto-subscribe peer protocol")
            nativeInbox == null -> ParsedRequest.Bad("nativeInbox must be a boolean")
            nativeInbox && request.owner == null -> ParsedRequest.Bad("nativeInbox requires a live owner")
            else -> ParsedRequest.Valid(request.copy(nativeInbox = nativeInbox, harness = harness))
        }
    }

    private fun autoSubscription(owner: NativeProcess?, peer: HarnessPeerConfig?): Boolean {
        if (owner == null || peer?.autoSubscribe != true) return false
        return peer.driver != HarnessPeerProtocol.CODEX
    }

    /** The answer to a register that went through: 201 for a new peer, 200 for one already there. */
    private suspend fun answered(
        name: String,
        outcome: PeerRegistration.Registered,
        owner: NativeProcess?,
    ): PeerAnswer {
        // A register that changed a peer already there stops its old helper (if any) before applying the new mode.
        if (outcome.outcome == RegisterOutcome.UPDATED) parts.helpers?.stop(name)
        val nativeInbox = name in store.nativeInboxes
        val pid = if (nativeInbox) owner?.pid else parts.helpers?.start(name)
        val socket = if (nativeInbox) ownerSocket(owner) else parts.helpers?.running(name)?.socket
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
                put("socket", socket)
                put("pid", pid)
            },
        )
    }

    private fun nativeInbox(body: JsonObject?, driver: HarnessPeerProtocol?): Boolean? = when (driver) {
        HarnessPeerProtocol.NATIVE, HarnessPeerProtocol.ACP -> true
        HarnessPeerProtocol.COMMAND, HarnessPeerProtocol.WEBHOOK -> false
        HarnessPeerProtocol.CODEX, null -> legacyNativeInbox(body)
    }

    private fun legacyNativeInbox(body: JsonObject?): Boolean? {
        if (body == null || !body.containsKey("nativeInbox")) return false
        return (body["nativeInbox"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }

    private fun ownerSocket(owner: NativeProcess?): String? {
        val pid = owner?.pid ?: return null
        return parts.registry.read().firstOrNull {
            it.availability != SessionAvailability.GONE && it.process.pid == pid
        }?.process?.messagingSocketPath
    }

    /** `sendTo` as given, or `["*"]` when absent; null when it is there but not a list of strings. */
    private fun sendTo(body: JsonObject?): List<String>? {
        val raw = body?.get("sendTo") ?: return listOf("*")
        val items = (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return items?.takeIf { list -> list.none { it == null } }?.filterNotNull()
    }
}

private sealed class ParsedRequest {
    data class Valid(val request: PeerRequest) : ParsedRequest()

    data class Bad(val reason: String) : ParsedRequest()
}
