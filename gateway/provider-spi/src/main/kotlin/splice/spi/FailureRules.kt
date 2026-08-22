// PORT-OF: splice/spi/UpstreamClient.kt (FailureRules + authBodyRe) @ 3879c4c — invariants unchanged: isEncryptedContentError stays PUBLIC and stays the ONE predicate the give-up classification and the RC-4 amend gate both key off (review 2026-07-24).
//
// The two predicates that read an upstream FAILURE (status + body) and answer a policy question
// about it. Was UpstreamClient.FailureRules; only the receiver moved.
//
// ITS OWN FILE, and that is the point: [FailureRules.isEncryptedContentError] is the ONE predicate
// the retry loop's GIVE_UP classification and the RC-4 amend gate must both key off. A review on
// 2026-07-24 found a narrower literal match on the amend side (in ResponsesProvider, another
// module) that let any upstream wording drift skip the recovery and land straight in give-up. A
// shared predicate that is easy to find is harder to re-implement.
package splice.spi

public class FailureRules {
    /** Does this upstream failure warrant the single-flight token refresh? */
    internal fun isAuthRefreshableFailure(status: Int, body: String): Boolean =
        status == UNAUTHORIZED || (status == FORBIDDEN && authBodyRe.containsMatchIn(body))

    /** Grok Build: 4xx + "encrypted_content" in the message → do not retry. PUBLIC because the
     *  RC-4 amend gate must key off the SAME predicate as this GIVE_UP classification (review
     *  2026-07-24: a narrower literal match on the amend side let any wording drift skip the
     *  recovery and land straight in give-up). */
    public fun isEncryptedContentError(status: Int, body: String): Boolean =
        status in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX && body.contains("encrypted_content", ignoreCase = true)
}

private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403

// xAI reports an expired/revoked OAuth token as 403 `unauthenticated:bad-credentials`,
// NOT 401 (grok-dead-head incident, 2026-07-18: refresh never fired, the head 403'd every
// turn until manual re-login). 401 is always refreshable; 403 only when the body says
// auth — a plan/permission 403 must not spend the single refresh.
// FILE SCOPE ON PURPOSE: one compiled Regex for the process. As a FailureRules field it would
// recompile on every construction, and planRetry constructs nothing but reads it per failure.
private val authBodyRe = Regex(
    "unauthenticated|bad-credentials|token (is )?(invalid|expired)|" +
        "(access|oauth2?) token could not be validated",
    RegexOption.IGNORE_CASE,
)

private const val CLIENT_ERROR_MIN = 400
private const val CLIENT_ERROR_MAX = 499
