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
package splice.upstream.failure

import splice.core.turn.FailureCause
import splice.core.util.ERR_SNIPPET
import splice.core.wire.HttpStatus

public class FailureRules {
    /** Does this upstream failure warrant the single-flight token refresh? */
    internal fun isAuthRefreshableFailure(status: Int, body: String): Boolean =
        status == HttpStatus.UNAUTHORIZED || (status == HttpStatus.FORBIDDEN && isAuthFailureBody(body))

    /** 403-body classifier (grok 2026-07-18): plan/permission 403s must not look like auth. */
    public fun isAuthFailureBody(body: String): Boolean = authBodyRe.containsMatchIn(body)

    /** V4-73: 402 Payment Required is a quota exhaustion for EVERY provider — deepseek answers
     *  Insufficient Balance as 402, and a spent account is not a malformed request. A vendor-neutral
     *  HTTP fact, which is why it lives here and not on the auth port: the port is where a VENDOR's
     *  own spelling goes, this is the protocol's. */
    public fun isQuotaExhaustionStatus(status: Int): Boolean = status == HttpStatus.PAYMENT_REQUIRED

    /** Grok Build: 4xx + "encrypted_content" in the message → do not retry. PUBLIC because the
     *  RC-4 amend gate must key off the SAME predicate as this GIVE_UP classification (review
     *  2026-07-24: a narrower literal match on the amend side let any wording drift skip the
     *  recovery and land straight in give-up). */
    public fun isEncryptedContentError(status: Int, body: String): Boolean =
        status in HttpStatus.BAD_REQUEST..CLIENT_ERROR_MAX &&
            body.contains("encrypted_content", ignoreCase = true)

    /** The verdicts on the bytes themselves: a re-send of the same bytes meets the same answer. */
    internal fun refusedAsSent(status: Int, text: String): String? = when {
        overflowed(status, text) -> "is a context overflow (no retry: the same bytes overflow again)"
        policyRefused(status, text) -> "is a content-policy refusal (no retry: the same bytes are refused again)"
        bodyRejected(status, text) ->
            "rejects the request body (no retry: the same bytes are rejected again): ${text.take(ERR_SNIPPET)}"
        modelMissing(status, text) ->
            "does not serve the requested model (no retry: a re-send asks for the same model): " +
                text.take(ERR_SNIPPET)
        else -> null
    }

    /** V4-117 audit: a 4xx other than a rate limit whose vendor CODE names a policy refusal (the
     *  classifier's CONTENT_FILTERED). Unlike a bare status, a code is the vendor's own verdict, and
     *  the retry matrix entitles that cause to no layer at any phase; retrying it spent the curve on
     *  an answer that could not change. Keyed the way [overflowed] is, so a 429 still reaches the
     *  cooldown branch. */
    private fun policyRefused(status: Int, text: String): Boolean =
        status in HttpStatus.BAD_REQUEST until HttpStatus.INTERNAL_SERVER_ERROR &&
            status != HttpStatus.TOO_MANY_REQUESTS &&
            UpstreamFailureClassifier.classify(FailureSource.HTTP, text, status).cause ==
            FailureCause.CONTENT_FILTERED

    /** A 400 invalid_request_error whose message names a field of the body (`messages.342.content.0.thinking.
     *  estimated_tokens: Extra inputs are not permitted`). The vendor validated the bytes we sent, and the same
     *  bytes fail the same validation: the 2026-10-11 burst re-sent one such body 4 times, each answered with the
     *  identical 400. A 400 that names no field of the body (a bare "Error", the muse prefill case that was OUR
     *  bug) keeps V4-62's curve. */
    private fun bodyRejected(status: Int, text: String): Boolean =
        status == HttpStatus.BAD_REQUEST && BODY_FIELD_REJECTION.containsMatchIn(text)

    /** A 404 or 400 saying the upstream has no such model: Ollama's `model "x" not found, try pulling it first`,
     *  vLLM's and OpenAI's "The model `x` does not exist", or the `model_not_found` code. A local runtime answers
     *  this until the operator pulls or loads the model, which outlasts any retry curve, so each re-send only
     *  delayed the runtime's own instruction reaching the client (spec v0.4.0 section 10, unavailable model). */
    private fun modelMissing(status: Int, text: String): Boolean =
        (status == HttpStatus.NOT_FOUND || status == HttpStatus.BAD_REQUEST) &&
            MODEL_MISSING.containsMatchIn(text)

    /** V4-167: a 4xx other than a rate limit, whose text says overflow. A 429 reading "too many tokens"
     *  is a per-minute token quota, which heals with time and must reach the cooldown branch; a 5xx is
     *  the server's own failure, and V4-62 retries it. Classified on the text before either, a TPM 429
     *  gave up, armed no cooldown, and told the client to compact a conversation that fit. */
    private fun overflowed(status: Int, text: String): Boolean =
        status in HttpStatus.BAD_REQUEST until HttpStatus.INTERNAL_SERVER_ERROR &&
            status != HttpStatus.TOO_MANY_REQUESTS &&
            UpstreamFailureClassifier.classify(FailureSource.HTTP, text, status).cause ==
            FailureCause.REQUEST_TOO_LARGE
}

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

// 499 stays local: nothing answers with it, it is only the top of the 4xx window tested above.
private const val CLIENT_ERROR_MAX = 499

/** The vendor's own validation line: type invalid_request_error and a message that opens with a path into the body. */
private val BODY_FIELD_REJECTION =
    Regex(""""type"\s*:\s*"invalid_request_error"[^}]*"message"\s*:\s*"(messages|system|tools)[.\[][^"]*:""")

/** The upstream's own "no such model": the `model_not_found` code, or a message naming a model that is not found or
 *  does not exist. The gap is bounded so an unrelated "not found" further along a long body cannot match. */
private val MODEL_MISSING =
    Regex(
        """"code"\s*:\s*"model_not_found"|\bmodel\b[^{}]{0,160}?\b(not found|does not exist)""",
        RegexOption.IGNORE_CASE,
    )
