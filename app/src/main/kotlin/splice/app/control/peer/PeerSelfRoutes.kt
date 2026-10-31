// NEW: Oct 19, 2026, moved verbatim out of PeersMount.kt by concern (ConcentrationLawTest): the peer-key routes of
// docs/specs/peer-messaging.md 3.3, which read one peer's journal. The send moved to PeerSendRoute.kt on Oct 27, 2026.
package splice.app.control.peer

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.http.JsonBody
import splice.sessions.peer.PeerPrincipal

// why: spec 3.3, a page of the inbox: 50 by default, at most 200, so one read stays a few hundred KB at most.
private const val DEFAULT_INBOX_LIMIT = 50

// why: spec 3.3, 200 records of at most 32 KiB text each is the most one inbox read may return.
private const val MAX_INBOX_LIMIT = 200

private val INBOX_KINDS = setOf("message", "receipt", "webhook", "codex")

/** A valid inbox query (spec 3.3). */
private data class InboxQuery(val after: Long, val limit: Int, val kinds: Set<String>)

/** The peer-key read routes (spec 3.3). The principal decides whose journal; no route takes a peer name. */
internal class PeerSelfRoutes(private val parts: PeerParts) {
    private val jsonBody = JsonBody()

    /** GET /api/peers/self/inbox?after&limit&kinds. */
    suspend fun inbox(call: ApplicationCall, principal: PeerPrincipal) {
        val query = inboxQuery(call)
        val mail = parts.mail
        val answer = when {
            query == null -> PeerReplies.refuse(
                HttpStatusCode.BadRequest,
                "after is a seq, limit 1 to $MAX_INBOX_LIMIT, and kinds of message, receipt, webhook and codex",
            )
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, PEER_MAIL_UNWIRED)
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
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, PEER_MAIL_UNWIRED)
            else -> {
                val read = mail.ack(principal.name, through)
                PeerReplies.ok(HttpStatusCode.OK, buildJsonObject { put("read", read) })
            }
        }
        answer.send(call)
    }

    /** GET /api/peers/self/sent/{msgId}: the send's outcome and the receipt that came back for it, if one did. A
     *  caller's id is the msg_id its send carried, so the same call looks a send up by it. */
    suspend fun sent(call: ApplicationCall, principal: PeerPrincipal) {
        val msgId = call.parameters["msgId"].orEmpty()
        val mail = parts.mail
        val record = mail?.sent(principal.name, msgId)
        val answer = when {
            mail == null -> PeerReplies.refuse(HttpStatusCode.ServiceUnavailable, PEER_MAIL_UNWIRED)
            record == null -> PeerReplies.refuse(HttpStatusCode.NotFound, "this peer sent no message with that id")
            else -> PeerReplies.ok(HttpStatusCode.OK, record)
        }
        answer.send(call)
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
