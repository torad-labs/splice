// NEW: failure-handling slice 3 (2026-10-26). Moved from GrokRefusals, which rode on the login provider: what xAI says
// differently from its API type's standard, which claude-grok's Replies reads before the standard does, on Chat
// Completions and on Responses alike (final-design.md section 12).
//
// Deleted with GrokRefusals, each now the standard's reading: the invalid_image rule (a 400 is Rejected and given up
// on), and xAI's spending-limit reading (xAI answers it with a standard 402, code personal-team-blocked:spending-limit,
// which the standard reads as NeedsPayment: 446 claude-grok journal lines since Oct 19). GrokForbidden keeps the
// freshness rule that decides whether this 403 renews the login.
package splice.provider.grok

import splice.core.failure.FailureCause
import splice.core.wire.HttpStatus
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ProviderDeviation

/**
 * xAI reports an expired or revoked OAuth token as 403 `unauthenticated:bad-credentials`, never 401 (the grok-dead-head
 * incident of 2026-07-18: the refresh never fired, and the head answered 403 to every turn until a manual sign-in), and
 * its string `error` form says the OAuth2 access token could not be validated. That 403 is a dead credential: it renews
 * the login once, as a 401 does, unless GrokForbidden finds the token demonstrably fresh, and a renewed login xAI still
 * refuses ends as a rejected login in xAI's words. A plan or billing 403 says none of these words and stays Forbidden.
 */
internal object GrokReplies : ProviderDeviation {
    override fun http(reply: FailedReply): FailureCause.Provider? = reply.takeIf(::deadCredential)
        ?.let { FailureCause.Provider.AuthRejected(it.owner, ErrorMessage.said(it.body)) }

    override fun deadCredential(reply: FailedReply): Boolean =
        reply.status == HttpStatus.FORBIDDEN && DEAD_TOKEN_WORDS.containsMatchIn(reply.body)
}

/** xAI's words for a token it no longer accepts. */
private val DEAD_TOKEN_WORDS = Regex(
    "unauthenticated|bad-credentials|token (is )?(invalid|expired)|(access|oauth2?) token could not be validated",
    RegexOption.IGNORE_CASE,
)
