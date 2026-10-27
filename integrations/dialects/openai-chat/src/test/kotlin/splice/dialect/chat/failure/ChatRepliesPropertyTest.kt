// NEW: failure-handling slice 3. The Chat Completions reader, held to final-design.md section 7's table over generated
// replies: every status the table names and many it does not, the OpenAI error object's codes and types (llama-server's
// numeric status as a code included), the words an overflow and a missing model are written in, Retry-After,
// retry-after-ms and the reset headers in any case, both owners of a login, error chunks, finish reasons and refusals.
// The expected cause is the table's row, written here from the table, never from the reader.
package splice.dialect.chat.failure

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.failure.ServerFault
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant
import kotlin.random.Random

private const val RUNS = 3_000

private val TABLE_STATUSES = listOf(400, 401, 402, 403, 404, 429, 500, 502, 503, 504)
private val UNNAMED_STATUSES = listOf(
    405, 406, 408, 409, 410, 412, 413, 415, 418, 422, 423, 425, 426, 428, 431, 451, 501, 505, 507, 520, 522, 524, 529,
)
private val CODES = listOf(
    "context_length_exceeded", "model_not_found", "content_policy_violation", "image_content_policy_violation",
    "invalid_prompt", "insufficient_quota", "rate_limit_exceeded", "server_error", "server_is_overloaded",
    "slow_down", "invalid_api_key", "invalid_parameter", "", "personal-team-blocked:spending-limit",
)
private val TYPES = listOf("invalid_request_error", "server_error", "insufficient_quota", "tokens", "requests", "")
private val UNKNOWN_CODES = listOf("weird_code", "personal-team-blocked:spending-limit", "1301", "E_MYSTERY", "")
private val CAPACITY = setOf("server_is_overloaded", "slow_down")
private val POLICY = setOf("content_policy_violation", "image_content_policy_violation")

/** Sentences that say nothing a row reads: no overflow, no model. */
private val PLAIN = listOf(
    "You exceeded your current quota, please check your plan and billing details",
    "The server had an error while processing your request",
    "You have run out of credits",
    "Incorrect API key provided",
    "Invalid value for 'temperature': expected a number",
    "Your organization must be verified to stream this request",
    "Request was rejected as a result of the safety system",
)
private val MODELS = listOf("gpt-5.6", "grok-4.20", "glm-5.3", "qwen3:32b", "deepseek-v4")

private sealed class Said(val text: String) {
    class Plain(text: String) : Said(text)
    class Overflow(text: String, val limit: Long?) : Said(text)
    class Model(text: String, val name: String) : Said(text)
}

private data class Pushback(
    val retryAfterS: Long?,
    val retryAfterMs: Long?,
    val resets: Map<String, Pair<Long, Boolean>>,
)

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
    val code: String,
    val type: String,
    val said: Said,
    val pushback: Pushback,
    val shape: Shape,
) {
    val owner: LoginOwner get() = shape.owner
    val answeredAt: Instant get() = shape.answeredAt
    val json: Boolean get() = shape.json

    val error: JsonObject get() = chunk(code, type, said.text)

    val body: String get() = if (json) error.toString() else "<html><head><title>$status</title></head></html>"

    fun reply(): FailedReply {
        val headers = buildMap {
            pushback.retryAfterS?.let { put(name("retry-after"), it.toString()) }
            pushback.retryAfterMs?.let { put(name("retry-after-ms"), it.toString()) }
            pushback.resets.forEach { (bucket, reset) ->
                put(name("x-ratelimit-reset-$bucket"), duration(reset.first))
                put(name("x-ratelimit-remaining-$bucket"), if (reset.second) "0" else "12")
            }
        }
        return FailedReply(status, headers, body, owner, NEVER_SENT, answeredAt)
    }

    private fun name(header: String) = if (shape.upperHeaders) header.uppercase() else header
}

private val NEVER_SENT = SentKey { _, _ -> false }

private fun chunk(code: String, type: String, message: String): JsonObject = buildJsonObject {
    putJsonObject("error") {
        put("message", message)
        if (type.isNotEmpty()) put("type", type)
        code.toIntOrNull()?.let { put("code", it) } ?: if (code.isNotEmpty()) put("code", code) else put("param", "x")
    }
}

/** A Go duration as OpenAI writes its reset headers ("1m30s", "250ms"). */
private fun duration(ms: Long): String = when {
    ms % 1_000 != 0L -> "${ms}ms"
    ms >= 60_000 -> "${ms / 60_000}m${(ms % 60_000) / 1_000}s"
    else -> "${ms / 1_000}s"
}

private fun said(random: Random): Said = when (random.nextInt(4)) {
    0 -> {
        val limit = random.nextLong(8_000, 2_000_000)
        Said.Overflow(
            "This model's maximum context length is $limit tokens. However, your messages resulted in " +
                "${limit + random.nextLong(1, 9_000)} tokens.",
            limit,
        )
    }
    1 -> Said.Overflow("the request exceeds the context window for this model", null)
    2 -> MODELS.random(random).let { Said.Model("The model `$it` does not exist or you do not have access to it.", it) }
    else -> Said.Plain(PLAIN.random(random))
}

private fun pushback(random: Random): Pushback = Pushback(
    retryAfterS = if (random.nextInt(3) == 0) random.nextLong(0, 90_000) else null,
    retryAfterMs = if (random.nextInt(3) == 0) random.nextLong(0, 90_000_000) else null,
    resets = listOf("requests", "tokens").filter { random.nextInt(3) == 0 }.associateWith {
        random.nextLong(1, 3_600_000) to random.nextBoolean()
    },
)

private fun case(random: Random, statuses: List<Int>, codes: List<String>, types: List<String> = TYPES): Case = Case(
    status = statuses.random(random),
    code = codes.random(random),
    type = types.random(random),
    said = said(random),
    pushback = pushback(random),
    shape = Shape(
        owner = LoginOwner.entries.random(random),
        answeredAt = Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000)),
        json = random.nextInt(8) != 0,
        upperHeaders = random.nextBoolean(),
    ),
)

private fun Case.names(word: String) = json && (code == word || type == word)

/** final-design.md section 7, OpenAI Chat Completions, row by row. */
private fun expected(c: Case): FailureCause.Provider {
    val words = ProviderWordsFilter.of(if (c.json) c.said.text else c.body)
    return when {
        c.status == 429 && c.names("insufficient_quota") ->
            FailureCause.Provider.NeedsPayment(Lift.Unstated, false, words)
        c.status == 429 ->
            FailureCause.Provider.Limited(statedRetry(c) ?: statedReset(c) ?: Lift.Unstated, null, words)
        c.status >= 500 -> serverRow(c, words)
        else -> requestRow(c, words)
    }
}

/** The rows about the request itself: its size, the model it asked for, the policy, its login and its permission. */
private fun requestRow(c: Case, words: ProviderWords?): FailureCause.Provider = when (c.status) {
    400 -> badRequestRow(c, words)
    401 -> FailureCause.Provider.AuthRejected(c.owner, words)
    402 -> FailureCause.Provider.NeedsPayment(Lift.Unstated, false, words)
    403 -> FailureCause.Provider.Forbidden(words)
    404 -> if (c.names("model_not_found")) {
        FailureCause.Provider.ModelMissing(modelNamed(c), words)
    } else {
        FailureCause.Provider.Unrecognized(true, words)
    }
    else -> FailureCause.Provider.Unrecognized(c.status in 400..499, words)
}

/** A 400: an overflow by code or by words, a missing model, a content-policy code, else a rejected request. */
private fun badRequestRow(c: Case, words: ProviderWords?): FailureCause.Provider {
    val overflow = (c.said as? Said.Overflow)?.takeIf { c.json }
    return when {
        c.names("context_length_exceeded") || overflow != null ->
            FailureCause.Provider.TooLong(overflow?.limit, false, words)
        c.names("model_not_found") -> FailureCause.Provider.ModelMissing(modelNamed(c), words)
        POLICY.any(c::names) -> FailureCause.Provider.Refused(RefusedBy.POLICY, words)
        else -> FailureCause.Provider.Rejected(false, words)
    }
}

/** The model this reply's own body named, which only its JSON carries. */
private fun modelNamed(c: Case): String = (c.said as? Said.Model)?.name?.takeIf { c.json }.orEmpty()

/** The rows about the provider's own side. A capacity code is an overload on any 5xx: kept from the shared
 *  classifier, a row the table does not list. */
private fun serverRow(c: Case, words: ProviderWords?): FailureCause.Provider = when {
    c.status == 503 || CAPACITY.any(c::names) ->
        FailureCause.Provider.Overloaded(statedRetry(c) ?: Lift.Unstated, false, words)
    c.status == 500 -> FailureCause.Provider.ServerError(ServerFault.INTERNAL, words)
    c.status == 502 -> FailureCause.Provider.ServerError(ServerFault.BAD_GATEWAY, words)
    c.status == 504 -> FailureCause.Provider.ServerError(ServerFault.GATEWAY_TIMEOUT, words)
    else -> FailureCause.Provider.Unrecognized(false, words)
}

/** Retry-After, then OpenAI's retry-after-ms. */
private fun statedRetry(c: Case): Lift? =
    (c.pushback.retryAfterS?.times(1_000) ?: c.pushback.retryAfterMs)
        ?.let { Lift.At(c.answeredAt.plusMillis(it), true, LiftSource.RETRY_AFTER) }

/** The reset of the buckets the reply says are spent, or of every bucket it states when it says none is. */
private fun statedReset(c: Case): Lift? {
    val stated = c.pushback.resets.values
    val spent = stated.filter { it.second }.ifEmpty { stated }
    return spent.maxOfOrNull { it.first }?.let { Lift.At(c.answeredAt.plusMillis(it), true, LiftSource.RESET_HEADER) }
}

/** The status OpenAI answers each code with, which an in-band chunk carrying it reads at. */
private val CODE_STATUS = mapOf(
    "context_length_exceeded" to 400,
    "content_policy_violation" to 400,
    "image_content_policy_violation" to 400,
    "invalid_prompt" to 400,
    "invalid_parameter" to 400,
    "invalid_api_key" to 401,
    "model_not_found" to 404,
    "insufficient_quota" to 429,
    "rate_limit_exceeded" to 429,
    "server_error" to 500,
    "server_is_overloaded" to 503,
    "slow_down" to 503,
    // llama-server writes its HTTP-equivalent status as a numeric code.
    "400" to 400,
    "404" to 404,
    "500" to 500,
    "503" to 503,
)

class ChatRepliesPropertyTest {
    @Test
    fun `every Chat error reply reads as the kind its status and error object name, at the lift it states`() {
        val random = Random(311)
        repeat(RUNS) {
            val c = case(random, TABLE_STATUSES, CODES)
            assertEquals(expected(c), ChatReplies.http(c.reply()), c.toString())
        }
    }

    @Test
    fun `a content filter finish reads as Refused(FILTER), and a refusal as Refused(MODEL) in the model's words`() {
        val random = Random(312)
        repeat(RUNS) {
            val owner = LoginOwner.entries.random(random)
            assertEquals(
                FailureCause.Provider.Refused(RefusedBy.FILTER),
                ChatReplies.event(FailedEvent(ChatReplies.FILTER_EVENT, JsonObject(emptyMap()), owner)),
            )
            val refusal = PLAIN.random(random) + ". " + PLAIN.random(random)
            assertEquals(
                FailureCause.Provider.Refused(RefusedBy.MODEL, ProviderWordsFilter.of(refusal)),
                ChatReplies.event(
                    FailedEvent(ChatReplies.REFUSAL_EVENT, buildJsonObject { put("refusal", refusal) }, owner),
                ),
            )
        }
    }

    @Test
    fun `a reply no table names is Unrecognized, with clientError set for a 4xx`() {
        val random = Random(313)
        repeat(RUNS) {
            val c = case(random, UNNAMED_STATUSES, UNKNOWN_CODES, listOf("", "requests", "gateway"))
            val words = ProviderWordsFilter.of(if (c.json) c.said.text else c.body)
            assertEquals(
                FailureCause.Provider.Unrecognized(c.status in 400..499, words),
                ChatReplies.http(c.reply()),
                c.toString(),
            )
            // In band, an overflow's own words name it (V4-164), so the chunk no row names says none.
            val plain = PLAIN.random(random)
            assertEquals(
                FailureCause.Provider.Unrecognized(false, ProviderWordsFilter.of(plain)),
                ChatReplies.event(FailedEvent(ChatReplies.ERROR_CHUNK, chunk(c.code, c.type, plain), c.owner)),
                c.toString(),
            )
        }
    }

    @Test
    fun `the same error reads as the same kind as an HTTP body and as an in-band event`() {
        val random = Random(314)
        repeat(RUNS) {
            val (code, status) = CODE_STATUS.entries.random(random).toPair()
            // An event carries no headers, so its twin reply carries none either.
            val c = case(random, listOf(status), listOf(code))
                .copy(json = true, pushback = Pushback(null, null, emptyMap()))
            assertEquals(expected(c), ChatReplies.http(c.reply()), c.toString())
            assertEquals(
                expected(c),
                ChatReplies.event(FailedEvent(ChatReplies.ERROR_CHUNK, c.error, c.owner)),
                c.toString(),
            )
        }
    }
}
