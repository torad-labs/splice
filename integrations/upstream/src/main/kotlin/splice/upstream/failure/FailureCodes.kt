// NEW: the vendor error codes UpstreamFailureClassifier keys on, each list named for what its codes mean. Exact
// codes, never wording: the classifier's cascade decides the order, and these decide what a code is.
package splice.upstream.failure

internal object FailureCodes {
    // The deterministic prompt-level refusals: the Responses API's own error enum
    // (the vendor SDK's ResponseError.code: invalid_prompt, bio_policy, image_content_policy_violation)
    // plus the ChatGPT backend's Trusted-Access gate (cyber_policy, live on claudex 2026-08-31/09-01).
    // Exact codes, never wording — the same rule as RETRYABLE_CODES below.
    val POLICY_REFUSAL_CODES = setOf(
        "cyber_policy",
        "bio_policy",
        "invalid_prompt",
        "image_content_policy_violation",
    )

    // The exact codes a status-less failure may be re-POSTed on: named transient server
    // conditions only. Anything else — known-deterministic or unknown — does not earn a
    // full-context replay on the say-so of its message text. Overload is NOT listed here: every
    // spelling of it (overloaded, overloaded_error, engine_/model_overloaded, server_is_overloaded)
    // is classified by shape in classifyContent, one rule instead of a list that rots a spelling
    // at a time (DR-71, then 2026-09-01).
    val RETRYABLE_CODES = setOf(
        "server_error",
        "internal_error",
        "internal_server_error",
        "timeout",
        "request_timeout",
        "service_unavailable",
        "temporarily_unavailable",
    )

    // The exact codes a status-less failure is never re-sent on: the request itself is wrong, so the identical
    // bytes get the identical answer (DR-10: an `invalid_parameter` whose message says "try again" was re-POSTed).
    // The OpenAI API's parameter and model codes, and the Responses API's ResponseError image codes. A code on
    // this list, POLICY_REFUSAL_CODES or RETRYABLE_CODES is known; any other is unknown and re-sent once.
    val DETERMINISTIC_CODES = setOf(
        "invalid_parameter",
        "invalid_request_error",
        "invalid_value",
        "invalid_type",
        "missing_required_parameter",
        "unknown_parameter",
        "unsupported_parameter",
        "unsupported_value",
        "string_above_max_length",
        "array_above_max_length",
        "model_not_found",
        "context_length_exceeded",
        "invalid_api_key",
        "invalid_image",
        "invalid_image_format",
        "invalid_base64_image",
        "invalid_image_url",
        "invalid_image_mode",
        "image_too_large",
        "image_too_small",
        "image_parse_error",
        "image_file_too_large",
        "image_file_not_found",
        "unsupported_image_media_type",
        "empty_image_file",
        "failed_to_download_image",
    )

    // The backend's two capacity codes by name (codex-rs PR #31058); `slow_down` does not spell
    // overload, so the shape rule alone would miss it.
    val CAPACITY_CODES = setOf("server_is_overloaded", "slow_down")
    val overloadCodeRe = Regex("overload", RegexOption.IGNORE_CASE)
}
