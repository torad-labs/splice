// NEW: Oct 27, 2026 (docs/peers.md, "Send as the peer"): what a send may carry besides its target and text, and how a
// send under a caller's id meets the sends this peer already made under it. A caller's id is the message's msg_id on the
// wire, so a receiver that keeps ids (a Splice peer) journals it once however often it comes. Sending the same text to
// the same target under an id that already went answers that first send again and writes nothing; another text or
// another target under it is refused. An id whose first send was refused before it left may be sent again; one whose
// first send failed part way may have arrived, so it goes again only to a receiver that keeps ids. A send can also be
// pinned to the process its target was resolved to: when another process now carries the name or the session id, the
// send is refused instead of reaching it.
package splice.app.control.peer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import splice.sessions.peer.journal.MessageDigests
import splice.sessions.registry.SessionRecord

// why: a msg_id rides in a frame attribute and a file name: a letter or digit, then letters, digits and `._:-`,
// 128 at most, which holds a UUID or a producer's own id with its namespace.
private val CALLER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

// why: a session-id prefix shorter than 8 characters names too many sessions, as PeerTargets has it.
private const val PREFIX_TARGET_CHARS = 8

/** The registry kind every Splice peer writes, whose inbox keeps each msg_id it journaled (PeerRegistryShapes). */
private const val KEEPS_IDS_KIND = "splice-peer"

private const val OUTBOX_SUBMITTED = "submitted"
private const val OUTBOX_FAILED = "failed"

/** The send this peer last made under a caller's id, as its outbox recorded it. */
internal data class PriorSend(
    val outcome: String,
    val digest: String?,
    val sessionId: String?,
    val name: String?,
    val address: String?,
)

/** The process a send is pinned to: the pid its target had when it was resolved, and that pid's kernel start. */
internal data class SendPin(val pid: Long, val procStart: String?)

/** A send's optional parts: the caller's [id], the [pin], and the SHA-256 of its text. */
internal data class PeerSendRequest(val id: String?, val pin: SendPin?, val digest: String) {
    /** Why the request may not go, before any target is read: a malformed id or pin, or null. */
    fun refusal(body: JsonObject?): String? {
        val rawId = body?.get("id")
        val rawPid = body?.get("pid")
        val rawStart = body?.get("procStart")
        return when {
            rawId != null && id == null -> "an id is a letter or digit, then letters, digits and ._:-, 128 at most"
            (rawPid != null || rawStart != null) && pin == null -> "a pin is a pid, and the procStart it had"
            else -> null
        }
    }
}

/** How a send under a caller's id meets this peer's earlier sends under it. */
internal sealed class IdTurn {
    /** No send under the id left, or the last one was refused before it left: send it. */
    data object Fresh : IdTurn()

    /** The last one may have arrived: send it only to a receiver that keeps ids. */
    data object Unconfirmed : IdTurn()

    /** The same message already went to the same target: answer that send again, and send nothing. */
    data object Repeat : IdTurn()

    data class Taken(val reason: String) : IdTurn()
}

internal object PeerSendRequests {
    fun of(body: JsonObject?, text: String): PeerSendRequest {
        val id = PeerReplies.string(body, "id")?.takeIf { CALLER_ID.matches(it) }
        val pid = (body?.get("pid") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
        val start = body?.get("procStart")
        val procStart = PeerReplies.string(body, "procStart")
        val pin = pid?.takeIf { start == null || procStart != null }?.let { SendPin(it, procStart) }
        return PeerSendRequest(id, pin, MessageDigests.of(text))
    }

    /** How [request] under its id meets [prior], the newest send under that id, for a send addressed to [to]. */
    fun turn(prior: PriorSend?, request: PeerSendRequest, to: String): IdTurn = when {
        prior == null -> IdTurn.Fresh
        prior.digest != request.digest -> IdTurn.Taken("another message was sent under this id")
        !sameTarget(prior, to) -> IdTurn.Taken("this id went to another target")
        prior.outcome == OUTBOX_SUBMITTED -> IdTurn.Repeat
        prior.outcome == OUTBOX_FAILED -> IdTurn.Unconfirmed
        else -> IdTurn.Fresh
    }

    /** Why [target] may not take this send, or null when it may: it is no longer the pinned process, or the id's last
     *  send may have reached it and it does not keep ids. */
    fun targetRefusal(target: SessionRecord, request: PeerSendRequest, turn: IdTurn): String? = when {
        request.pin?.let { moved(target, it) } == true -> "the target is no longer the process it was resolved to"
        turn == IdTurn.Unconfirmed && target.client.kind != KEEPS_IDS_KIND ->
            "the last send under this id may have reached its target; look it up with `splice peer status`, " +
                "or send under a new id"
        else -> null
    }

    private fun moved(target: SessionRecord, pin: SendPin): Boolean {
        val start = pin.procStart
        return target.process.pid != pin.pid || (start != null && target.process.procStart != start)
    }

    private fun sameTarget(prior: PriorSend, to: String): Boolean {
        val prefix = to.length >= PREFIX_TARGET_CHARS && prior.sessionId?.startsWith(to) == true
        return prefix || to in listOfNotNull(prior.sessionId, prior.name, prior.address)
    }
}
