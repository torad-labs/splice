// NEW: failure-handling slice 3 (2026-10-26). The Anthropic Messages reader (final-design.md section 7): every head
// that speaks Anthropic's API (claude-splice, claude-kimi, claude-deepseek) reads a failed reply, an in-band error
// event and a stop reason that is no clean end here, once. A provider that answers differently declares it in its own
// module as a ProviderDeviation (KimiReplies), and the foreign-key rule runs before both (Replies).
//
// The stop-reason rows move here from PassthroughTerminalState: a refusal is the model's verdict and quotes the
// category and explanation it carries (trace fae124199450 on claude-splice), model_context_window_exceeded is an answer
// that outgrew the window, and pause_turn keeps today's overload, since Claude Code 2.1.310 does not continue a paused
// turn (slice 0b's record).
package splice.dialect.anthropic.failure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.ServerFault
import splice.core.usage.FIVE_HOUR_CLAIM
import splice.core.usage.PlanLimit
import splice.core.usage.PlanLimits
import splice.core.usage.SEVEN_DAY_CLAIM
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ReplyFacts
import splice.upstream.failure.ReplyReader
import java.time.Instant

/** Reads Anthropic's failures: an HTTP status with its `{"type":"error","error":{"type","message"}}` body, an `error`
 *  event of the same shape ([ERROR_EVENT]), and a message_delta's stop_reason that ends the answer as a failure
 *  ([STOP_EVENT], one of [FAILED_STOPS]). */
public object AnthropicReplies : ReplyReader {
    /** An `error` event; its payload is the event as sent. */
    public const val ERROR_EVENT: String = "error"

    /** A message_delta's stop_reason that is no clean end; its payload carries `stop_reason` and `stop_details`. */
    public const val STOP_EVENT: String = "stop_reason"

    /** The stop reasons that end an answer as a failure, which [AnthropicStopReasons] reads. */
    public val FAILED_STOPS: Set<String> get() = AnthropicStopReasons.FAILED

    override fun http(reply: FailedReply): FailureCause.Provider {
        val body = JsonScalars.objectOrNull(bodies, reply.body)
        val error = body?.get("error") as? JsonObject
        val type = JsonScalars.str(error, "type").orEmpty()
        val message = if (body == null) reply.body else ErrorMessage.inObject(body)
        return row(reply.status, type, message, reply.owner, reply)
    }

    override fun event(event: FailedEvent): FailureCause.Provider = when (event.type) {
        STOP_EVENT -> AnthropicStopReasons.read(event.payload)
        else -> {
            val error = event.payload["error"] as? JsonObject
            val type = JsonScalars.str(error, "type").orEmpty()
            val message = ErrorMessage.inObject(event.payload)
            TYPE_STATUS[type]?.let { row(it, type, message, event.owner, null) }
                ?: FailureCause.Provider.Unrecognized(clientError = false, ProviderWordsFilter.of(message))
        }
    }

    /** A 429's spent plan window, as Anthropic's unified family names it (V4-233). */
    override fun planLimit(reply: FailedReply): PlanLimit? = reply.takeIf { it.status == HttpStatus.TOO_MANY_REQUESTS }
        ?.let { PlanLimits.of({ name -> it.header(name) }, it.answeredAt.epochSecond) }

    /** The table's row for [status]; [reply] is the reply whose headers state a lift, null for an event. */
    private fun row(
        status: Int,
        type: String,
        message: String?,
        owner: LoginOwner,
        reply: FailedReply?,
    ): FailureCause.Provider {
        val said = ProviderWordsFilter.of(message)
        val text = message.orEmpty()
        return when {
            type == BILLING_ERROR || status == HttpStatus.PAYMENT_REQUIRED ->
                FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = false, said)
            status == HttpStatus.TOO_MANY_REQUESTS -> limited(reply, said)
            status < HttpStatus.INTERNAL_SERVER_ERROR -> requestSide(status, text, owner, said)
            else -> serverSide(status, reply, said)
        }
    }

    /** The rows about the request itself: its size, its login, its permission and the model it asked for. */
    private fun requestSide(
        status: Int,
        text: String,
        owner: LoginOwner,
        said: ProviderWords?,
    ): FailureCause.Provider = when (status) {
        HttpStatus.BAD_REQUEST -> badRequest(text, said)
        HttpStatus.UNAUTHORIZED -> FailureCause.Provider.AuthRejected(owner, said)
        HttpStatus.FORBIDDEN -> FailureCause.Provider.Forbidden(said)
        HttpStatus.CONTENT_TOO_LARGE -> FailureCause.Provider.TooLong(null, whileAnswering = false, said)
        HttpStatus.NOT_FOUND -> ReplyFacts.modelNamed(text).takeIf(String::isNotEmpty)
            ?.let { FailureCause.Provider.ModelMissing(it, said) }
            ?: FailureCause.Provider.Unrecognized(clientError = true, said)
        else -> FailureCause.Provider.Unrecognized(clientError = status in CLIENT_ERRORS, said)
    }

    /** A 400: an answer that outgrew the window, carrying the limit it named, else a rejected request. */
    private fun badRequest(text: String, said: ProviderWords?): FailureCause.Provider =
        if (ReplyFacts.overflows(text)) {
            FailureCause.Provider.TooLong(ReplyFacts.contextLimit(text), whileAnswering = false, said)
        } else {
            FailureCause.Provider.Rejected(staleReasoning = false, said)
        }

    /** A 429: when Retry-After says, else when the spent plan window resets, and which window it is. */
    private fun limited(reply: FailedReply?, said: ProviderWords?): FailureCause.Provider {
        val plan = reply?.let(::planLimit)
        val reset = plan?.let { Lift.At(Instant.ofEpochSecond(it.resetEpochSeconds), true, LiftSource.RESET_HEADER) }
        val lift = reply?.let(ReplyFacts::retryAfterLift) ?: reset ?: Lift.Unstated
        return FailureCause.Provider.Limited(lift, plan?.let { window(it.claim) }, said)
    }

    private fun window(claim: String): LimitWindow = when {
        claim == FIVE_HOUR_CLAIM -> LimitWindow.FIVE_HOUR
        claim == SEVEN_DAY_CLAIM -> LimitWindow.WEEKLY
        claim.startsWith("${SEVEN_DAY_CLAIM}_") -> LimitWindow.MODEL_WEEKLY
        else -> LimitWindow.OTHER
    }

    private fun serverSide(
        status: Int,
        reply: FailedReply?,
        said: ProviderWords?,
    ): FailureCause.Provider = when (status) {
        HttpStatus.INTERNAL_SERVER_ERROR -> FailureCause.Provider.ServerError(ServerFault.INTERNAL, said)
        HttpStatus.BAD_GATEWAY -> FailureCause.Provider.ServerError(ServerFault.BAD_GATEWAY, said)
        HttpStatus.GATEWAY_TIMEOUT -> FailureCause.Provider.ServerError(ServerFault.GATEWAY_TIMEOUT, said)
        HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.OVERLOADED -> FailureCause.Provider.Overloaded(
            reply?.let(ReplyFacts::retryAfterLift) ?: Lift.Unstated,
            loading = false,
            said,
        )
        else -> FailureCause.Provider.Unrecognized(clientError = status in CLIENT_ERRORS, said)
    }
}

private const val BILLING_ERROR = "billing_error"

private val CLIENT_ERRORS = HttpStatus.BAD_REQUEST until HttpStatus.INTERNAL_SERVER_ERROR

/** Anthropic's error types at the status its API answers each with, so an in-band error reads as its HTTP twin. */
private val TYPE_STATUS = mapOf(
    "invalid_request_error" to HttpStatus.BAD_REQUEST,
    "authentication_error" to HttpStatus.UNAUTHORIZED,
    BILLING_ERROR to HttpStatus.PAYMENT_REQUIRED,
    "permission_error" to HttpStatus.FORBIDDEN,
    "not_found_error" to HttpStatus.NOT_FOUND,
    "request_too_large" to HttpStatus.CONTENT_TOO_LARGE,
    "rate_limit_error" to HttpStatus.TOO_MANY_REQUESTS,
    "api_error" to HttpStatus.INTERNAL_SERVER_ERROR,
    "timeout_error" to HttpStatus.GATEWAY_TIMEOUT,
    "overloaded_error" to HttpStatus.OVERLOADED,
)

/** Bodies are the provider's, so they are read leniently. */
private val bodies = Json {
    isLenient = true
    ignoreUnknownKeys = true
}
