// NEW: failure-handling slice 3 (2026-10-26). The rows OpenAI's two APIs share (final-design.md section 7): Chat
// Completions reads every failure by them, and Responses takes them for every HTTP status. They live here, beside the
// envelope both read, because neither OpenAI dialect may depend on the other.
//
// An in-band error is read by the same rows as an HTTP reply: its code stands for the status OpenAI answers it with
// (llama-server writes that status as the code itself), so the same error reads as the same kind either way. A code no
// table names is Unrecognized and not the provider's verdict on the request, which the retry re-sends once.
package splice.upstream.failure

import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.core.wire.HttpStatus
import java.time.Duration

/** Reads an OpenAI-shaped failure by its status, refined by its error object's code: the rows OpenAI's two APIs
 *  share, which each of their standards reads before adding its own, and the reading a provider that declared none of
 *  its own gets ([splice.upstream.Provider.replies]). */
public object OpenAiReplies {
    /** An HTTP reply, by its status and the error object its body carries (a body that is no JSON is its message). */
    public fun http(reply: FailedReply): FailureCause.Provider {
        val error = OpenAiErrorEnvelope.of(reply.body) ?: OpenAiError("", "", reply.body)
        return row(reply.status, error, reply.owner, Stated(reply))
    }

    /** An in-band [error], read at the status its code stands for; one with no code says an overflow only in its words,
     *  which a 400 reads (V4-164: a stream's overflow reached Claude Code as a retried error instead of the "prompt is
     *  too long" it compacts on). An event carries no headers, so a limit or an overload it names states no lift. */
    public fun inBand(error: OpenAiError, owner: LoginOwner): FailureCause.Provider {
        val status = statusOf(error)
            ?: HttpStatus.BAD_REQUEST.takeIf { ReplyFacts.overflows("${error.code} ${error.message.orEmpty()}") }
            ?: return FailureCause.Provider.Unrecognized(clientError = false, said(error))
        return row(status, error, owner, Stated(null))
    }

    /** An in-band failure, by the error object the event carries, wherever the event keeps it. */
    public fun event(event: FailedEvent): FailureCause.Provider =
        inBand(OpenAiErrorEnvelope.of(event.payload), event.owner)

    /** The status [error] stands for: llama-server's own status written as a numeric code, else the status OpenAI
     *  answers its code (or, where it gave none, its type) with; null for an error no table names. A vendor's own
     *  numeric code outside the error statuses ("1301") is a kind, never a status. */
    public fun statusOf(error: OpenAiError): Int? {
        val numeric = error.code.toIntOrNull()
        if (numeric != null && numeric in ERROR_STATUSES) return numeric
        val key = key(error)
        return CODE_STATUS[key] ?: HttpStatus.SERVICE_UNAVAILABLE.takeIf { CAPACITY.containsMatchIn(key) }
    }

    /** The provider's own words in [error], as a line may quote them. */
    public fun said(error: OpenAiError): ProviderWords? = ProviderWordsFilter.of(error.message)

    /** The code an error is keyed by: its string code, else its type (a numeric code is a status or a vendor kind). */
    private fun key(error: OpenAiError): String =
        error.code.takeIf { it.toIntOrNull() == null }.orEmpty().ifEmpty { error.type }.lowercase()

    private fun row(status: Int, error: OpenAiError, owner: LoginOwner, stated: Stated): FailureCause.Provider {
        val said = said(error)
        return when {
            status == HttpStatus.TOO_MANY_REQUESTS && error.names(INSUFFICIENT_QUOTA) ->
                FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = false, said)
            status == HttpStatus.TOO_MANY_REQUESTS -> FailureCause.Provider.Limited(stated.limitLift(), null, said)
            status >= HttpStatus.INTERNAL_SERVER_ERROR -> serverSide(status, error, said, stated)
            else -> requestSide(status, error, owner, said)
        }
    }

    /** The rows about the request itself: its size, the model it asked for, its login and its permission. */
    private fun requestSide(
        status: Int,
        error: OpenAiError,
        owner: LoginOwner,
        said: ProviderWords?,
    ): FailureCause.Provider = when {
        status == HttpStatus.BAD_REQUEST -> badRequest(error, said)
        status == HttpStatus.UNAUTHORIZED -> FailureCause.Provider.AuthRejected(owner, said)
        status == HttpStatus.PAYMENT_REQUIRED -> FailureCause.Provider.NeedsPayment(Lift.Unstated, false, said)
        status == HttpStatus.FORBIDDEN -> FailureCause.Provider.Forbidden(said)
        status == HttpStatus.NOT_FOUND && error.names(MODEL_NOT_FOUND) -> missing(error, said)
        else -> unnamed(status, said)
    }

    /** A 400: an overflow by code or by its words, a missing model, a content-policy code, else a rejected request. */
    private fun badRequest(error: OpenAiError, said: ProviderWords?): FailureCause.Provider = when {
        error.names(CONTEXT_LENGTH_EXCEEDED) || ReplyFacts.overflows("${error.code} ${error.message.orEmpty()}") ->
            FailureCause.Provider.TooLong(ReplyFacts.contextLimit(error.message), whileAnswering = false, said)
        error.names(MODEL_NOT_FOUND) -> missing(error, said)
        CONTENT_POLICY.any(error::names) -> FailureCause.Provider.Refused(RefusedBy.POLICY, said)
        else -> FailureCause.Provider.Rejected(staleReasoning = false, said)
    }

    /** A 5xx. A capacity code (the backend's server_is_overloaded and slow_down, codex-rs PR #31058, or any code
     *  spelling an overload) is an overload whatever the 5xx, as the shared classifier read it; 503 is one too. */
    private fun serverSide(
        status: Int,
        error: OpenAiError,
        said: ProviderWords?,
        stated: Stated,
    ): FailureCause.Provider = when {
        status == HttpStatus.SERVICE_UNAVAILABLE || CAPACITY.containsMatchIn(key(error)) ->
            FailureCause.Provider.Overloaded(stated.pushback() ?: Lift.Unstated, loading = false, said)
        status == HttpStatus.INTERNAL_SERVER_ERROR -> FailureCause.Provider.ServerError(ServerFault.INTERNAL, said)
        status == HttpStatus.BAD_GATEWAY -> FailureCause.Provider.ServerError(ServerFault.BAD_GATEWAY, said)
        status == HttpStatus.GATEWAY_TIMEOUT -> FailureCause.Provider.ServerError(ServerFault.GATEWAY_TIMEOUT, said)
        else -> unnamed(status, said)
    }

    private fun missing(error: OpenAiError, said: ProviderWords?): FailureCause.Provider =
        FailureCause.Provider.ModelMissing(ReplyFacts.modelNamed(error.message), said)

    /** A status no row names: the provider's verdict on the request when it is a 4xx. */
    private fun unnamed(status: Int, said: ProviderWords?): FailureCause.Provider =
        FailureCause.Provider.Unrecognized(clientError = status in CLIENT_ERRORS, said)
}

/** What a reply states about when to come back. An in-band event, with no [reply], states nothing. */
private class Stated(private val reply: FailedReply?) {
    /** Retry-After, then OpenAI's retry-after-ms: the pushback an overload or a limit asks for. */
    fun pushback(): Lift.At? {
        val reply = reply ?: return null
        return ReplyFacts.retryAfterLift(reply)
            ?: reply.header("retry-after-ms")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                ?.let { ReplyFacts.liftAfter(reply, it.toLong(), LiftSource.RETRY_AFTER) }
    }

    /** When a rate limit lifts: the pushback, else the reset of each bucket OpenAI's x-ratelimit-reset-requests and
     *  -tokens say is empty, or of both when they say neither is, since a request needs both. */
    fun limitLift(): Lift = pushback() ?: resetLift() ?: Lift.Unstated

    private fun resetLift(): Lift.At? {
        val reply = reply ?: return null
        val buckets = listOf("requests", "tokens").mapNotNull { bucket ->
            val resetMs = reply.header("x-ratelimit-reset-$bucket")?.let(::durationMs) ?: return@mapNotNull null
            resetMs to (reply.header("x-ratelimit-remaining-$bucket")?.trim() == "0")
        }
        val spent = buckets.filter { it.second }.ifEmpty { buckets }
        return spent.maxOfOrNull { it.first }?.let { ReplyFacts.liftAfter(reply, it, LiftSource.RESET_HEADER) }
    }

    /** A Go-style duration ("1h2m3.5s", "20ms") in milliseconds; null when [text] is not one. */
    private fun durationMs(text: String): Long? {
        val trimmed = text.trim()
        if (!WHOLE_DURATION.matches(trimmed)) return null
        return DURATION_PART.findAll(trimmed).sumOf { part ->
            val unitMs = when (part.groupValues[2]) {
                "ms" -> 1L
                "s" -> Duration.ofSeconds(1).toMillis()
                "m" -> Duration.ofMinutes(1).toMillis()
                else -> Duration.ofHours(1).toMillis()
            }
            part.groupValues[1].toDouble() * unitMs
        }.toLong()
    }
}

private const val MODEL_NOT_FOUND = "model_not_found"
private const val CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded"
private const val INSUFFICIENT_QUOTA = "insufficient_quota"

// why: the last status in the 5xx class, so the range below covers every failure status an HTTP reply can carry.
private const val LAST_ERROR_STATUS = 599

private val ERROR_STATUSES = HttpStatus.BAD_REQUEST..LAST_ERROR_STATUS
private val CLIENT_ERRORS = HttpStatus.BAD_REQUEST until HttpStatus.INTERNAL_SERVER_ERROR

/** The content-policy codes: OpenAI's own, and the Responses API's image refusal. */
private val CONTENT_POLICY = listOf("content_policy_violation", "image_content_policy_violation")

/** Any code spelling an overload, and the backend's slow_down, which does not spell one (codex-rs PR #31058). */
private val CAPACITY = Regex("overload|^slow_down$")

/** The status each code OpenAI documents stands for, with the transient and request codes the shared classifier
 *  named (FailureCodes before slice 3) at the status they mean, so an in-band error reads as its HTTP twin does. */
private val CODE_STATUS: Map<String, Int> = buildMap {
    listOf(
        CONTEXT_LENGTH_EXCEEDED, "invalid_prompt", "invalid_request_error", "invalid_parameter", "invalid_value",
        "invalid_type", "missing_required_parameter", "unknown_parameter", "unsupported_parameter", "unsupported_value",
        "string_above_max_length", "array_above_max_length", "invalid_image", "invalid_image_format",
        "invalid_base64_image", "invalid_image_url", "invalid_image_mode", "image_too_large", "image_too_small",
        "image_parse_error", "image_file_too_large", "image_file_not_found", "unsupported_image_media_type",
        "empty_image_file", "failed_to_download_image",
    ).forEach { put(it, HttpStatus.BAD_REQUEST) }
    CONTENT_POLICY.forEach { put(it, HttpStatus.BAD_REQUEST) }
    put("invalid_api_key", HttpStatus.UNAUTHORIZED)
    put(MODEL_NOT_FOUND, HttpStatus.NOT_FOUND)
    put(INSUFFICIENT_QUOTA, HttpStatus.TOO_MANY_REQUESTS)
    put("rate_limit_exceeded", HttpStatus.TOO_MANY_REQUESTS)
    listOf("server_error", "internal_error", "internal_server_error").forEach {
        put(it, HttpStatus.INTERNAL_SERVER_ERROR)
    }
    listOf("timeout", "request_timeout").forEach { put(it, HttpStatus.GATEWAY_TIMEOUT) }
    listOf("service_unavailable", "temporarily_unavailable").forEach { put(it, HttpStatus.SERVICE_UNAVAILABLE) }
}

private val WHOLE_DURATION = Regex("""(?:\d+(?:\.\d+)?(?:ms|h|m|s))+""")
private val DURATION_PART = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")
