// NEW: failure-handling slice 3. The Anthropic Messages reader, held to final-design.md section 7's table over
// generated replies: every status and error type Anthropic documents, the words it writes an overflow and a missing
// model in, its Retry-After and unified plan headers in any case, both owners of a login, and bodies that are no JSON.
// The expected cause is the table's row, written here from the table, never from the reader.
package splice.dialect.anthropic.failure

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.upstream.failure.ERROR_EVENT
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant
import kotlin.random.Random

private const val RUNS = 3_000

/** Anthropic's error types at the status its API answers each with (docs.anthropic.com, errors). */
private val TYPE_STATUS = mapOf(
    "invalid_request_error" to 400,
    "authentication_error" to 401,
    "billing_error" to 402,
    "permission_error" to 403,
    "not_found_error" to 404,
    "request_too_large" to 413,
    "rate_limit_error" to 429,
    "api_error" to 500,
    "timeout_error" to 504,
    "overloaded_error" to 529,
)
private val TABLE_STATUSES = listOf(400, 401, 402, 403, 404, 413, 429, 500, 502, 503, 504, 529)
private val UNNAMED_STATUSES = listOf(
    405, 406, 409, 410, 411, 412, 415, 416, 418, 421, 422, 423, 425, 426, 428, 431, 451, 501, 505, 507, 508, 511, 520,
    522, 524, 530,
)

/** Sentences that say nothing a row reads: no overflow, no model, no billing. */
private val PLAIN = listOf(
    "Your request could not be processed right now",
    "Number of request tokens has exceeded your per-minute rate limit",
    "Overloaded",
    "This organization has been disabled",
    "Your account does not have access to this feature",
    "Internal server error",
    "messages.3.content.0.text: field required",
    "The request body is not valid",
)
private val CATEGORIES = listOf("cyber", "bio", "violent_extremism", "self_harm")
private val EXPLANATIONS = listOf(
    "This request asks for working malware against a named system",
    "The answer would give step by step synthesis of a controlled agent",
    "I cannot help with this part of the request",
)
private val MODELS = listOf("claude-opus-5", "claude-sonnet-4-5-20250929", "claude-fable-5-1", "kimi-k2")

private sealed class Said(val text: String) {
    class Plain(text: String) : Said(text)
    class Overflow(text: String, val limit: Long?) : Said(text)
    class Model(text: String, val name: String) : Said(text)
}

private data class Plan(val claim: String, val rejected: Boolean, val resetEpochSecond: Long)

/** How one reply arrived, apart from what it said: whose login it answered, when it came, whether its body is the
 *  API's own JSON or a gateway's page, and whether its headers came upper-cased. */
private data class Shape(
    val owner: LoginOwner,
    val answeredAt: Instant,
    val json: Boolean,
    val upperHeaders: Boolean,
)

private data class Case(
    val status: Int,
    val type: String,
    val said: Said,
    val retryAfterS: Long?,
    val plan: Plan?,
    val shape: Shape,
) {
    val owner: LoginOwner get() = shape.owner
    val answeredAt: Instant get() = shape.answeredAt
    val json: Boolean get() = shape.json

    val body: String
        get() = if (json) envelope(type, said.text).toString() else "<html><body>$status ${said.text}</body></html>"

    fun reply(): FailedReply {
        val headers = buildMap {
            retryAfterS?.let { put(name("retry-after"), it.toString()) }
            plan?.let {
                put(name("anthropic-ratelimit-unified-status"), if (it.rejected) "rejected" else "allowed")
                put(name("anthropic-ratelimit-unified-representative-claim"), it.claim)
                put(name("anthropic-ratelimit-unified-reset"), it.resetEpochSecond.toString())
            }
        }
        return FailedReply(status, headers, body, owner, NEVER_SENT, answeredAt)
    }

    private fun name(header: String) =
        if (shape.upperHeaders) header.split('-').joinToString("-") { it.uppercase() } else header
}

private val NEVER_SENT = SentKey { _, _ -> false }

private fun envelope(type: String, message: String): JsonObject = buildJsonObject {
    put("type", "error")
    putJsonObject("error") {
        put("type", type)
        put("message", message)
    }
    put("request_id", "req_011CUXu8f1Wj3nP2aD5Y9q7Z")
}

private fun said(random: Random): Said = when (random.nextInt(4)) {
    0 -> {
        val limit = random.nextLong(8_000, 2_000_000)
        val sent = limit + random.nextLong(1, 90_000)
        Said.Overflow("prompt is too long: $sent tokens > $limit maximum", limit)
    }
    1 -> listOf(
        Said.Overflow("prompt is too long", null),
        Said.Overflow("input length and max_tokens exceed context limit", null),
        Said.Overflow("The input exceeds the context window of this model", null),
    ).random(random)
    2 -> MODELS.random(random).let { Said.Model("model: $it", it) }
    else -> Said.Plain(PLAIN.random(random))
}

private fun plan(random: Random, at: Instant): Plan? {
    if (random.nextInt(3) == 0) return null
    val claim = listOf("five_hour", "seven_day", "seven_day_opus", "overage").random(random)
    val within = if (claim == "five_hour") 5 * 3_600L else 7 * 86_400L
    return Plan(claim, random.nextBoolean(), at.epochSecond + random.nextLong(60, within))
}

private fun case(random: Random, statuses: List<Int>, types: List<String>): Case {
    val at = Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000))
    return Case(
        status = statuses.random(random),
        type = types.random(random),
        said = said(random),
        retryAfterS = if (random.nextBoolean()) random.nextLong(0, 100_000) else null,
        plan = plan(random, at),
        shape = Shape(
            owner = LoginOwner.entries.random(random),
            answeredAt = at,
            json = random.nextInt(8) != 0,
            upperHeaders = random.nextBoolean(),
        ),
    )
}

/** final-design.md section 7, Anthropic Messages, row by row. */
private fun expected(c: Case): FailureCause.Provider {
    val words = ProviderWordsFilter.of(saidIn(c))
    return when {
        typeIn(c) == "billing_error" || c.status == 402 ->
            FailureCause.Provider.NeedsPayment(Lift.Unstated, false, words)
        c.status == 429 ->
            FailureCause.Provider.Limited(retryAfter(c) ?: planReset(c) ?: Lift.Unstated, window(c), words)
        c.status < 500 -> requestRow(c, words)
        else -> serverRow(c, words)
    }
}

/** The error type the reply named, which only its own JSON body carries. */
private fun typeIn(c: Case): String = if (c.json) c.type else ""

/** What the provider said: its message in its own body, the whole page otherwise. */
private fun saidIn(c: Case): String = if (c.json) c.said.text else c.body

private fun retryAfter(c: Case): Lift? =
    c.retryAfterS?.let { Lift.At(c.answeredAt.plusSeconds(it), true, LiftSource.RETRY_AFTER) }

/** The rows about the request itself. */
private fun requestRow(c: Case, words: ProviderWords?): FailureCause.Provider {
    val model = c.said as? Said.Model
    return when (c.status) {
        400 -> badRequestRow(c, words)
        401 -> FailureCause.Provider.AuthRejected(c.owner, words)
        403 -> FailureCause.Provider.Forbidden(words)
        413 -> FailureCause.Provider.TooLong(null, false, words)
        404 -> model?.let { FailureCause.Provider.ModelMissing(it.name, words) }
            ?: FailureCause.Provider.Unrecognized(true, words)
        else -> FailureCause.Provider.Unrecognized(c.status in 400..499, words)
    }
}

/** A 400: an overflow carries the limit its words named, and anything else is a rejected request. */
private fun badRequestRow(c: Case, words: ProviderWords?): FailureCause.Provider =
    (c.said as? Said.Overflow)
        ?.let { FailureCause.Provider.TooLong(it.limit, false, words) }
        ?: FailureCause.Provider.Rejected(false, words)

/** The rows about the provider's own side. */
private fun serverRow(c: Case, words: ProviderWords?): FailureCause.Provider = when (c.status) {
    500 -> FailureCause.Provider.ServerError(ServerFault.INTERNAL, words)
    502 -> FailureCause.Provider.ServerError(ServerFault.BAD_GATEWAY, words)
    504 -> FailureCause.Provider.ServerError(ServerFault.GATEWAY_TIMEOUT, words)
    503, 529 -> FailureCause.Provider.Overloaded(retryAfter(c) ?: Lift.Unstated, false, words)
    else -> FailureCause.Provider.Unrecognized(false, words)
}

/** The plan window a rejected unified family names; a spend bucket (overage) names none. */
private fun window(c: Case): LimitWindow? = c.plan?.takeIf { it.rejected }?.let {
    when (it.claim) {
        "five_hour" -> LimitWindow.FIVE_HOUR
        "seven_day" -> LimitWindow.WEEKLY
        "overage" -> null
        else -> LimitWindow.MODEL_WEEKLY
    }
}

private fun planReset(c: Case): Lift? = c.plan?.takeIf { window(c) != null }
    ?.let { Lift.At(Instant.ofEpochSecond(it.resetEpochSecond), true, LiftSource.RESET_HEADER) }

class AnthropicRepliesPropertyTest {
    @Test
    fun `every Anthropic error reply reads as the kind its status and error type name, at the lift it states`() {
        val random = Random(301)
        repeat(RUNS) {
            val c = case(random, TABLE_STATUSES, TYPE_STATUS.keys.toList())
            assertEquals(expected(c), AnthropicReplies.http(c.reply()), c.toString())
        }
    }

    @Test
    fun `a stop_reason refusal reads as Refused(MODEL) carrying its category and explanation`() {
        val random = Random(302)
        repeat(RUNS) {
            val category = CATEGORIES.random(random).takeIf { random.nextInt(4) != 0 }
            val explanation = EXPLANATIONS.random(random).takeIf { random.nextInt(4) != 0 }
            val quoted = when {
                category != null && explanation != null -> "$explanation ($category)."
                explanation != null -> "$explanation."
                category != null -> "$category."
                else -> null
            }
            val payload = buildJsonObject {
                put("stop_reason", "refusal")
                putJsonObject("stop_details") {
                    put("type", "refusal")
                    category?.let { put("category", it) }
                    explanation?.let { put("explanation", it) }
                }
            }
            val cause = AnthropicReplies.event(FailedEvent(AnthropicReplies.STOP_EVENT, payload, LoginOwner.SPLICE))
            assertEquals(FailureCause.Provider.Refused(RefusedBy.MODEL, ProviderWordsFilter.of(quoted)), cause)
            assertEquals(quoted, (cause as FailureCause.Provider.Refused).said?.text)
        }
    }

    @Test
    fun `model_context_window_exceeded reads as TooLong after content, and pause_turn keeps today's overload`() {
        val random = Random(303)
        repeat(RUNS) {
            val owner = LoginOwner.entries.random(random)
            val exceeded = buildJsonObject {
                put("stop_reason", "model_context_window_exceeded")
                if (random.nextBoolean()) putJsonObject("stop_details") { put("type", "context") }
            }
            assertEquals(
                FailureCause.Provider.TooLong(null, whileAnswering = true),
                AnthropicReplies.event(FailedEvent(AnthropicReplies.STOP_EVENT, exceeded, owner)),
            )
            val paused = buildJsonObject { put("stop_reason", "pause_turn") }
            // Slice 0b's record: Claude Code 2.1.310 does not continue a paused turn, so pause_turn keeps its reading.
            assertEquals(
                FailureCause.Provider.Overloaded(Lift.Unstated, loading = false),
                AnthropicReplies.event(FailedEvent(AnthropicReplies.STOP_EVENT, paused, owner)),
            )
        }
    }

    @Test
    fun `a reply no table names is Unrecognized, with clientError set for a 4xx`() {
        val random = Random(304)
        val unknownTypes = listOf("weird_error", "quota_error", "", "gateway_error")
        repeat(RUNS) {
            val c = case(random, UNNAMED_STATUSES, TYPE_STATUS.keys.filter { it != "billing_error" } + unknownTypes)
            val words = ProviderWordsFilter.of(if (c.json) c.said.text else c.body)
            assertEquals(
                FailureCause.Provider.Unrecognized(c.status in 400..499, words),
                AnthropicReplies.http(c.reply()),
                c.toString(),
            )
            val inBand = envelope(unknownTypes.random(random), c.said.text)
            assertEquals(
                FailureCause.Provider.Unrecognized(false, ProviderWordsFilter.of(c.said.text)),
                AnthropicReplies.event(FailedEvent(ERROR_EVENT, inBand, c.owner)),
            )
        }
    }

    @Test
    fun `the same error reads as the same kind as an HTTP body and as an in-band event`() {
        val random = Random(305)
        repeat(RUNS) {
            val (type, status) = TYPE_STATUS.entries.random(random).toPair()
            // An event carries no headers, so its twin reply carries none either.
            val base = case(random, listOf(status), listOf(type))
            val c = base.copy(retryAfterS = null, plan = null, shape = base.shape.copy(json = true))
            val event = envelope(type, c.said.text)
            assertEquals(expected(c), AnthropicReplies.http(c.reply()), c.toString())
            assertEquals(
                expected(c),
                AnthropicReplies.event(FailedEvent(ERROR_EVENT, event, c.owner)),
                c.toString(),
            )
        }
    }
}
