// NEW: failure-handling slice 3 (2026-10-26). A provider's reader of its own failures: the foreign-key rule every API
// type shares, then the provider's declared deviation, then its API type's standard reader. Each provider composes its
// own in its wiring (Provider.replies), so a reply is read once, at its boundary, the same way for every head of one
// API type unless that head's provider declared otherwise (final-design.md sections 6, 7 and 12).
package splice.upstream.failure

import splice.core.failure.FailureCause
import splice.core.failure.LoginOwner
import splice.core.failure.ServerFault
import splice.core.usage.PlanLimit
import splice.core.wire.HttpStatus

/** [standard] reads what [deviation] does not claim. */
public class Replies(private val standard: ReplyReader, private val deviation: ProviderDeviation? = null) :
    ReplyReader {
    override fun http(reply: FailedReply): FailureCause.Provider =
        foreignKey(reply) ?: deviation?.http(reply) ?: standard.http(reply)

    override fun event(event: FailedEvent): FailureCause.Provider = deviation?.event(event) ?: standard.event(event)

    /** Whether this provider names [reply] a dead credential though its status is not 401 (ProviderDeviation). */
    public fun deadCredential(reply: FailedReply): Boolean = deviation?.deadCredential(reply) == true

    override fun planLimit(reply: FailedReply): PlanLimit? = deviation?.planLimit(reply) ?: standard.planLimit(reply)

    /**
     * V4-242: during the Codex outage of 2026-09-25 the provider answered 401 invalid_api_key naming a masked service
     * key (`sk-svcac…fvMA`) while the account had sent its own sign-in token. Any provider can fail its own check this
     * way, so the rule holds on every API type: a 401 whose body names masked keys, none of which the sent credential
     * can be, is the provider's own server error. The login stays in the pool and no key fragment reaches a line. A
     * body that names no masked key, and a forwarded client login splice never reads, say nothing either way.
     */
    private fun foreignKey(reply: FailedReply): FailureCause.Provider? {
        if (reply.status != HttpStatus.UNAUTHORIZED || reply.owner == LoginOwner.CLIENT) return null
        val masked = MASKED.findAll(reply.body).map { it.groupValues[1] to it.groupValues[2] }.toList()
        if (masked.isEmpty() || masked.any { (head, tail) -> reply.sent.couldBe(head, tail) }) return null
        return FailureCause.Provider.ServerError(ServerFault.FOREIGN_KEY, ErrorMessage.said(reply.body))
    }
}

/** A masked key as providers write one everywhere: a visible head, a run of stars, a visible tail. */
private val MASKED = Regex("""([A-Za-z0-9_-]+)\*{4,}([A-Za-z0-9_-]+)""")
