// NEW: Oct 19, 2026, moved verbatim out of PeersMount.kt by concern (ConcentrationLawTest): the peer-key routes of
// docs/specs/peer-messaging.md 3.3, which send as one peer and read its journal.
package splice.app.control.mount

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars
import splice.http.JsonBody
import splice.sessions.note.PeerTarget
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerPrincipal
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.registry.SessionRecord

// why: spec 1.1 and 3.4, the text cap after defusing; the route refuses a longer raw text with 413 before it is built.
private const val MAX_TEXT_BYTES = 32_768

// why: spec 3.3, a page of the inbox: 50 by default, at most 200, so one read stays a few hundred KB at most.
private const val DEFAULT_INBOX_LIMIT = 50
private const val MAX_INBOX_LIMIT = 200

private val INBOX_KINDS = setOf("message", "receipt", "webhook")

/** A valid inbox query (spec 3.3). */
private data class InboxQuery(val after: Long, val limit: Int, val kinds: Set<String>)

/** The peer-key routes (spec 3.3). The principal decides whose journal and whose name; no route takes a peer name. */
internal class PeerSelfRoutes(private val parts: PeerParts, private val rate: PeerSendRate) {
    private val store = parts.store
    private val targets = PeerTargets(parts.registry)
    private val jsonBody = JsonBody()

    /** POST /api/peers/self/send: resolve the target, check scope and rate, build the sender, send, record, answer. */
    suspend fun send(call: ApplicationCall, principal: PeerPrincipal) {
        sendAnswer(principal, jsonBody.parse(call)).send(call)
    }

    /** GET /api/peers/self/inbox?after&limit&kinds. */
    suspend fun inbox(call: ApplicationCall, principal: PeerPrincipal) {
        val query = inboxQuery(call)
        val mail = parts.mail
        val answer = when {
            query == null -> PeerReplies.refuse(
                HttpStatusCode.BadRequest,
                "after is a seq, limit 1 to $MAX_INBOX_LIMIT, and kinds of message, receipt and webhook",
            )
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, UNWIRED)
            else -> {
                val page = mail.inbox(principal.name, query.after, query.limit, query.kinds)
                PeerReplies.ok(
                    HttpStatusCode.OK,
                    buildJsonObject {
                        put("items", JsonArray(page.items))
                        put("next", page.next)
                        put("unread", page.unread)
                    },
                )
            }
        }
        answer.send(call)
    }

    /** POST /api/peers/self/inbox/ack {"through": seq}. */
    suspend fun ack(call: ApplicationCall, principal: PeerPrincipal) {
        val through = JsonScalars.long(jsonBody.parse(call), "through")?.takeIf { it >= 0 }
        val mail = parts.mail
        val answer = when {
            through == null -> PeerReplies.refuse(HttpStatusCode.BadRequest, "body must be {\"through\": <seq>}")
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, UNWIRED)
            else -> {
                val read = mail.ack(principal.name, through)
                PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("read", read) })
            }
        }
        answer.send(call)
    }

    /** GET /api/peers/self/sent/{msgId}: the send's outcome and the receipt that came back for it, if one did. */
    suspend fun sent(call: ApplicationCall, principal: PeerPrincipal) {
        val msgId = call.parameters["msgId"].orEmpty()
        val mail = parts.mail
        val record = mail?.sent(principal.name, msgId)
        val answer = when {
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, UNWIRED)
            record == null -> PeerReplies.refuse(HttpStatusCode.NotFound, "this peer sent no message with that id")
            else -> PeerReplies.ok(HttpStatusCode.OK, record)
        }
        answer.send(call)
    }

    private suspend fun sendAnswer(principal: PeerPrincipal, body: JsonObject?): PeerAnswer {
        val config = store.get(principal.name) ?: return PeerReplies.refuse(HttpStatusCode.NotFound, NO_PEER)
        val to = PeerReplies.string(body, "to")?.trim()?.takeIf { it.isNotEmpty() }
        val text = PeerReplies.string(body, "text")
        if (to == null || text == null) {
            return PeerReplies.refuse(HttpStatusCode.BadRequest, "body must be {\"to\": …, \"text\": …}")
        }
        return textRefusal(text) ?: resolved(config, targets.resolve(to), text)
    }

    /** A target that is not one running session alone on its socket is refused with the resolver's reason. */
    private suspend fun resolved(config: PeerConfig, target: PeerTarget, text: String): PeerAnswer = when (target) {
        is PeerTarget.One -> deliver(config, target.record, text)
        is PeerTarget.None -> PeerReplies.refuse(HttpStatusCode.NotFound, target.reason)
        is PeerTarget.Ambiguous -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
        is PeerTarget.NotRunning -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
        is PeerTarget.NoSocket -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
        is PeerTarget.SharedSocket -> PeerReplies.refuse(HttpStatusCode.Conflict, target.reason)
    }

    private fun textRefusal(text: String): PeerAnswer? = when {
        text.isBlank() -> PeerReplies.refuse(HttpStatusCode.BadRequest, "the text is empty")
        text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES ->
            PeerReplies.refuse(HttpStatusCode.PayloadTooLarge, "a text is at most $MAX_TEXT_BYTES bytes")
        else -> null
    }

    /** Scope, then rate, then the helper: the order spec 3.3 gives, after the target resolved. */
    private suspend fun deliver(config: PeerConfig, target: SessionRecord, text: String): PeerAnswer {
        val refusal = when {
            target.sessionId == config.sessionId ->
                PeerReplies.refuse(HttpStatusCode.Conflict, "the target is this peer itself")
            !store.mayReach(config, target.name, target.sessionId) ->
                PeerReplies.refuse(HttpStatusCode.Forbidden, "the target is outside this peer's sendTo")
            else -> null
        }
        if (refusal != null) return refusal
        val wait = rate.take(config.name)
        if (wait is SendAllowance.Wait) {
            return PeerReplies.refuse(HttpStatusCode.TooManyRequests, "over the send rate", wait.seconds)
        }
        val helper = parts.helpers?.running(config.name)
        val outbox = parts.outbox
        return if (helper == null || outbox == null) {
            PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, NOT_RUNNING)
        } else {
            submit(config, target, text, PeerSender(socket = helper.socket, name = config.name), outbox)
        }
    }

    /** The sender is the principal's registration and its running helper, never anything from the request. */
    private suspend fun submit(
        config: PeerConfig,
        target: SessionRecord,
        text: String,
        sender: PeerSender,
        outbox: PeerOutbox,
    ): PeerAnswer {
        val outcome = outbox.send(target, text, sender)
        val msgId = (outcome as? SendOutcome.Submitted)?.msgId
        parts.mail?.recordSend(config.name, SentRecord(msgId, target, text.toByteArray(Charsets.UTF_8).size, outcome))
        return when (outcome) {
            is SendOutcome.Submitted -> PeerReplies.ok(
                HttpStatusCode.Accepted,
                buildJsonObject {
                    put("msgId", outcome.msgId)
                    putJsonObject("to") {
                        put("name", target.name)
                        put("sessionId", target.sessionId)
                    }
                },
            )
            is SendOutcome.Refused -> PeerReplies.refuse(HttpStatusCode.fromValue(outcome.httpStatus), outcome.reason)
            is SendOutcome.Failed -> PeerReplies.refuse(HttpStatusCode.BadGateway, outcome.reason)
        }
    }

    private fun inboxQuery(call: ApplicationCall): InboxQuery? {
        val query = call.request.queryParameters
        val after = bounded(query["after"], 0L, 0L..Long.MAX_VALUE)
        val limit = bounded(query["limit"], DEFAULT_INBOX_LIMIT.toLong(), 1L..MAX_INBOX_LIMIT.toLong())
        val kinds = query["kinds"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: INBOX_KINDS
        val known = kinds.isNotEmpty() && INBOX_KINDS.containsAll(kinds)
        return if (after == null || limit == null) null else InboxQuery(after, limit.toInt(), kinds).takeIf { known }
    }

    /** [absent] when the parameter is missing; null when it is there but not a whole number in [range]. */
    private fun bounded(text: String?, absent: Long, range: LongRange): Long? =
        if (text == null) absent else text.toLongOrNull()?.takeIf { it in range }
}
