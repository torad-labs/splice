// NEW: Oct 27, 2026, moved out of PeerSelfRoutes.kt with the send's new parts (docs/specs/peer-messaging.md 3.3,
// docs/peers.md "Send as the peer"): POST /api/peers/self/send. It resolves the target, checks scope and rate, and sends
// as the principal's peer through its running helper. A caller's id makes the send idempotent and a pin ties it to the
// process its target was resolved to (PeerSendRequest); the rate is the peer's own limit (PeerLimits).
package splice.app.control.peer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.http.JsonBody
import splice.sessions.note.PeerTarget
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerPrincipal
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.store.PeerLimits
import splice.sessions.registry.SessionRecord

// why: spec 1.1 and 3.4, the text cap after defusing; the route refuses a longer raw text with 413 before it is built.
private const val SEND_TEXT_MAX_BYTES = 32_768

/** One send's text and its optional parts, and how its id met this peer's earlier sends. */
private data class Sending(val text: String, val request: PeerSendRequest, val turn: IdTurn)

internal class PeerSendRoute(
    private val parts: PeerParts,
    private val rate: PeerSendRate,
    private val limits: PeerLimits,
) {
    private val store = parts.store
    private val targets = PeerTargets(parts.registry)
    private val jsonBody = JsonBody()

    suspend fun send(call: ApplicationCall, principal: PeerPrincipal) {
        sendAnswer(principal, jsonBody.parse(call)).send(call)
    }

    private suspend fun sendAnswer(principal: PeerPrincipal, body: JsonObject?): PeerAnswer {
        val config = store.get(principal.name) ?: return PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        val to = PeerReplies.string(body, "to")?.trim()?.takeIf { it.isNotEmpty() }
        val text = PeerReplies.string(body, "text")
        if (to == null || text == null) {
            return PeerReplies.refuse(HttpStatusCode.BadRequest, "body must be {\"to\": …, \"text\": …}")
        }
        return textRefusal(text) ?: requested(config, to, text, body)
    }

    /** The id and pin read, then the id's earlier sends met: a repeat answers the first send again. */
    private suspend fun requested(config: PeerConfig, to: String, text: String, body: JsonObject?): PeerAnswer {
        val request = PeerSendRequests.of(body, text)
        val malformed = request.refusal(body)
        val prior = request.id?.takeIf { malformed == null }?.let { id -> parts.mail?.prior(config.name, id) }
        val turn = PeerSendRequests.turn(prior, request, to)
        return when {
            malformed != null -> PeerReplies.refuse(HttpStatusCode.BadRequest, malformed)
            turn is IdTurn.Taken -> PeerReplies.refuse(HttpStatusCode.Conflict, turn.reason)
            turn == IdTurn.Repeat && prior != null -> repeated(request.id.orEmpty(), prior)
            else -> resolved(config, targets.resolve(to), Sending(text, request, turn))
        }
    }

    /** A target that is not one running session alone on its socket is refused with the resolver's reason. */
    private suspend fun resolved(config: PeerConfig, target: PeerTarget, sending: Sending): PeerAnswer =
        when (target) {
            is PeerTarget.One -> deliver(config, target.record, sending)
            is PeerTarget.None -> PeerReplies.refuse(HttpStatusCode.NotFound, target.reason)
            is PeerTarget.Ambiguous -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
            is PeerTarget.NotRunning -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
            is PeerTarget.NoSocket -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
            is PeerTarget.SharedSocket -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
        }

    private fun textRefusal(text: String): PeerAnswer? = when {
        text.isBlank() -> PeerReplies.refuse(HttpStatusCode.BadRequest, "the text is empty")
        text.toByteArray(Charsets.UTF_8).size > SEND_TEXT_MAX_BYTES ->
            PeerReplies.refuse(HttpStatusCode.PayloadTooLarge, "a text is at most $SEND_TEXT_MAX_BYTES bytes")
        else -> null
    }

    /** Scope, the pin and the id, then rate, then the helper: spec 3.3's order, after the target resolved. */
    private suspend fun deliver(config: PeerConfig, target: SessionRecord, sending: Sending): PeerAnswer {
        val refusal = refusal(config, target, sending)
        if (refusal != null) return refusal
        val wait = rate.take(config.name, limits.of(config.name))
        if (wait is SendAllowance.Wait) {
            return PeerReplies.refuse(HttpStatusCode.TooManyRequests, "over the send rate", wait.seconds)
        }
        val helper = parts.helpers?.running(config.name)
        val outbox = parts.outbox
        return if (helper == null || outbox == null) {
            PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, HELPER_NOT_RUNNING)
        } else {
            submit(config, target, sending, PeerSender(socket = helper.socket, name = config.name), outbox)
        }
    }

    private fun refusal(config: PeerConfig, target: SessionRecord, sending: Sending): PeerAnswer? {
        val moved = PeerSendRequests.targetRefusal(target, sending.request, sending.turn)
        return when {
            target.sessionId == config.sessionId ->
                PeerReplies.refuse(HttpStatusCode.Conflict, "the target is this peer itself")
            !store.mayReach(config, target.name, target.sessionId) ->
                PeerReplies.refuse(HttpStatusCode.Forbidden, "the target is outside this peer's sendTo")
            moved != null -> PeerReplies.refuse(HttpStatusCode.Conflict, moved)
            else -> null
        }
    }

    /** The sender is the principal's registration and its running helper, never anything from the request. */
    private suspend fun submit(
        config: PeerConfig,
        target: SessionRecord,
        sending: Sending,
        sender: PeerSender,
        outbox: PeerOutbox,
    ): PeerAnswer {
        val request = sending.request
        val outcome = outbox.send(target, sending.text, sender, request.id)
        val msgId = (outcome as? SendOutcome.Submitted)?.msgId ?: request.id
        val bytes = sending.text.toByteArray(Charsets.UTF_8).size
        parts.mail?.recordSend(config.name, SentRecord(msgId, target, bytes, outcome, request.digest))
        return when (outcome) {
            is SendOutcome.Submitted ->
                PeerReplies.ok(HttpStatusCode.Accepted, sent(outcome.msgId, target.name, target.sessionId))
            is SendOutcome.Refused -> PeerReplies.refuse(HttpStatusCode.fromValue(outcome.httpStatus), outcome.reason)
            is SendOutcome.Failed -> PeerReplies.refuse(HttpStatusCode.BadGateway, outcome.reason)
        }
    }

    /** The first send under [id] answered again: nothing is written, and the rate is not charged. */
    private fun repeated(id: String, prior: PriorSend): PeerAnswer =
        PeerReplies.ok(HttpStatusCode.OK, sent(id, prior.name, prior.sessionId, repeat = true))

    private fun sent(msgId: String, name: String?, sessionId: String?, repeat: Boolean = false): JsonObject =
        buildJsonObject {
            put("msgId", msgId)
            putJsonObject("to") {
                put("name", name)
                put("sessionId", sessionId)
            }
            if (repeat) put("repeat", true)
        }
}
