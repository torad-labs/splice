// NEW: failure-handling slice 4 (2026-10-27). What Anthropic says to a Claude subscription login differently from the
// Messages standard, which the Claude head's Replies reads before the standard does (final-design.md section 12).
package splice.provider.anthropic

import splice.core.failure.AccountBlock
import splice.core.failure.FailureCause
import splice.core.failure.OwnerStep
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.upstream.failure.ERROR_EVENT
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ProviderDeviation

/** Where the owner of a Claude account accepts Anthropic's terms, as Anthropic's answer names it. */
private const val CLAUDE_AI = "claude.ai"

/**
 * Anthropic's one documented deviation for a Claude login: an account its owner must act on.
 *
 * At 2:52:10 PM CT on Oct 27, 2026, Anthropic answered claude-splice with a 400 invalid_request_error reading "We've
 * updated our Consumer Terms and Privacy Policy. You'll need to accept them in claude.ai with the email in /status to
 * continue." (daemon.log). The standard reads a 400 as a request the provider rejected, so splice ended 210 turns in
 * one minute saying the request was rejected as sent. The answer is about the account, not the request: every request
 * on any login of that account meets it until the account's owner accepts the terms in claude.ai, which no re-send or
 * renewal changes. It reads as Forbidden with that block, whatever status or type carries it, so the turn moves to
 * another login, the login that met it is held, and a line names the step and where its owner takes it.
 */
public object ClaudeReplies : ProviderDeviation {
    /** How Anthropic's answer names terms the account's owner has not accepted yet. */
    private val updatedTerms = Regex("""updated our Consumer Terms""", RegexOption.IGNORE_CASE)

    override fun http(reply: FailedReply): FailureCause.Provider? =
        blocked(ErrorMessage.of(reply.body), ErrorMessage.said(reply.body))

    override fun event(event: FailedEvent): FailureCause.Provider? {
        if (event.type != ERROR_EVENT) return null
        val message = ErrorMessage.inObject(event.payload)
        return blocked(message, ProviderWordsFilter.of(message))
    }

    private fun blocked(message: String?, said: ProviderWords?): FailureCause.Provider? =
        if (message != null && updatedTerms.containsMatchIn(message)) {
            FailureCause.Provider.Forbidden(said, AccountBlock(OwnerStep.ACCEPT_TERMS, CLAUDE_AI))
        } else {
            null
        }
}
