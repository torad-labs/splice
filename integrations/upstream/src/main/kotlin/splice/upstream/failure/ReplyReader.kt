// NEW: failure-handling slice 3 (2026-10-26). Each provider failure is read once, at its boundary, by its API type's
// reader: an HTTP reply where its status, headers and body are in hand, and an in-band failure where the dialect's
// stream state meets it. What reads it is the provider's Replies: the foreign-key rule every API type shares, then the
// provider's declared deviation, then its API type's standard reader (final-design.md sections 6 and 7).
package splice.upstream.failure

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.LoginOwner
import splice.core.usage.PlanLimit
import java.time.Instant

/** Reads one provider failure into the one cause it is. */
public interface ReplyReader {
    /** A reply that was no stream: a non-2xx status with its headers and body. */
    public fun http(reply: FailedReply): FailureCause.Provider

    /** A failure that arrived in band, after the provider's stream began. */
    public fun event(event: FailedEvent): FailureCause.Provider

    /** The plan window [reply] says is spent, exactly as the provider named it (V4-233), for the plan hold that keeps
     *  a login off until it resets. Slice 5's hold reads the cause instead; until then the window's own name (a
     *  model's seven-day window, codex's window in minutes) is kept here. Null for any reply that names none. */
    public fun planLimit(reply: FailedReply): PlanLimit? = null
}

/**
 * One failed HTTP reply as it arrived. [headers] are keyed as they came; [header] finds one by name whatever its case.
 * [owner] is whose login the attempt sent, and [sent] answers whether a masked key the body names could be it.
 * [answeredAt] is when the reply came, which a stated duration is measured from. [bodyRefusedAsTooLarge]: the
 * Responses WebSocket peer already closed this body's socket with 1009 (message too big), so this reply is the second
 * refusal of the same bytes.
 */
public data class FailedReply(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val owner: LoginOwner,
    val sent: SentKey,
    val answeredAt: Instant,
    val bodyRefusedAsTooLarge: Boolean = false,
) {
    /** The header named [name], whatever case the reply spelled it in. */
    public fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Never the secret: [sent] holds none, and the body is the provider's. */
    override fun toString(): String = "FailedReply(status=$status, owner=$owner, body=${body.take(TO_STRING_BODY)})"
}

/** One failure that arrived in band: the [type] its reader names it by and the event's own [payload]. The vocabulary of
 *  [type] is each API type's reader's own, written in its KDoc. [owner] is whose login the turn sends. */
public data class FailedEvent(val type: String, val payload: JsonObject, val owner: LoginOwner)

/** Whether a masked key a reply names could be the credential this attempt sent (head and tail of the mask).
 *  In process only: never stored, logged or carried past the reader. A login splice does not hold could be any key. */
public fun interface SentKey {
    public fun couldBe(head: String, tail: String): Boolean
}

/** How much of a body a reply's own text shows: enough to tell replies apart in a failed assertion, no more. */
private const val TO_STRING_BODY = 120
