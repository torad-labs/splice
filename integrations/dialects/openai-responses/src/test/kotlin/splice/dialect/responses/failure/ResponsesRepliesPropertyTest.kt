// NEW: failure-handling slice 3. The Responses reader, held to final-design.md section 7's table over generated
// replies and events: an HTTP status reads by the Chat rows, a 4xx naming encrypted_content is stale reasoning, a 4xx
// after the websocket closed the same body with 1009 is too long; response.failed and error events in every shape the
// API and its proxies send are read by their code; incomplete reasons and refusal parts have their own rows. The
// expected cause is the table's row, written here from the table, never from the reader.
package splice.dialect.responses.failure

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
private val UNNAMED_STATUSES = listOf(405, 408, 409, 410, 413, 415, 422, 451, 501, 505, 520, 524, 529)
private val CODES = listOf(
    "context_length_exceeded", "model_not_found", "content_policy_violation", "image_content_policy_violation",
    "invalid_prompt", "insufficient_quota", "rate_limit_exceeded", "server_error", "server_is_overloaded",
    "slow_down", "invalid_api_key", "invalid_encrypted_content", "",
)
private val UNKNOWN_CODES = listOf("weird_code", "websocket_hiccup", "E_MYSTERY")
private val CAPACITY = setOf("server_is_overloaded", "slow_down")
private val POLICY = setOf("content_policy_violation", "image_content_policy_violation")

/** Sentences that say nothing a row reads: no overflow, no model, no encrypted content. */
private val PLAIN = listOf(
    "You exceeded your current quota, please check your plan and billing details",
    "The server had an error while processing your request",
    "Your input was flagged as potentially violating our usage policy",
    "Bad Request",
    "Rate limit reached for requests",
)
private const val STALE = "Invalid 'input[12].encrypted_content': the reasoning item could not be decrypted"

private sealed class Said(val text: String) {
    class Plain(text: String) : Said(text)
    class Overflow(text: String, val limit: Long?) : Said(text)
    class Model(text: String, val name: String) : Said(text)
    class Stale : Said(STALE)
}

/** How one reply arrived, apart from what it said: whose login it answered, when it came, whether its body is the
 *  API's own JSON or a gateway's page, and whether the websocket already refused these bytes with a 1009 close. */
private data class Shape(
    val owner: LoginOwner,
    val answeredAt: Instant,
    val json: Boolean,
    val refusedOverWebSocket: Boolean,
)

private data class Case(
    val status: Int,
    val code: String,
    val said: Said,
    val retryAfterS: Long?,
    val shape: Shape,
) {
    val owner: LoginOwner get() = shape.owner
    val answeredAt: Instant get() = shape.answeredAt
    val json: Boolean get() = shape.json
    val refusedOverWebSocket: Boolean get() = shape.refusedOverWebSocket

    val error: JsonObject
        get() = buildJsonObject {
            putJsonObject("error") {
                put("message", said.text)
                put("type", if (status >= 500) "server_error" else "invalid_request_error")
                if (code.isNotEmpty()) put("code", code)
            }
        }

    val body: String get() = if (json) error.toString() else "<html><body>$status Bad Gateway</body></html>"

    fun reply(): FailedReply = FailedReply(
        status,
        retryAfterS?.let { mapOf("Retry-After" to it.toString()) }.orEmpty(),
        body,
        owner,
        NEVER_SENT,
        answeredAt,
        bodyRefusedAsTooLarge = refusedOverWebSocket,
    )
}

/** The codes a response.failed or error event carries, which the standard reads at the status each stands for. */
private val EVENT_CODES = listOf(
    "server_error",
    "rate_limit_exceeded",
    "insufficient_quota",
    "context_length_exceeded",
    "invalid_prompt",
    "server_is_overloaded",
    "slow_down",
    "invalid_api_key",
)

private val NEVER_SENT = SentKey { _, _ -> false }

private fun said(random: Random): Said = when (random.nextInt(5)) {
    0 -> {
        val limit = random.nextLong(8_000, 2_000_000)
        Said.Overflow("This model's maximum context length is $limit tokens.", limit)
    }
    1 -> "gpt-5.6-terra".let { Said.Model("The model `$it` does not exist or you do not have access to it.", it) }
    2 -> Said.Stale()
    else -> Said.Plain(PLAIN.random(random))
}

private fun case(random: Random, statuses: List<Int>, codes: List<String>): Case = Case(
    status = statuses.random(random),
    code = codes.random(random),
    said = said(random),
    retryAfterS = if (random.nextBoolean()) random.nextLong(0, 90_000) else null,
    shape = Shape(
        owner = LoginOwner.entries.random(random),
        answeredAt = Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000)),
        json = random.nextInt(8) != 0,
        refusedOverWebSocket = random.nextInt(4) == 0,
    ),
)

/** The Chat rows, which the Responses table takes for every HTTP status. */
private fun chatRow(c: Case): FailureCause.Provider {
    val words = ProviderWordsFilter.of(if (c.json) c.said.text else c.body)
    return when {
        c.status == 429 && names(c, "insufficient_quota") ->
            FailureCause.Provider.NeedsPayment(Lift.Unstated, false, words)
        c.status == 429 -> FailureCause.Provider.Limited(retry(c), null, words)
        c.status >= 500 -> serverRow(c, words)
        else -> requestRow(c, words)
    }
}

/** Whether this reply's error object names [word], by its code or by the type its status carries. */
private fun names(c: Case, word: String): Boolean {
    val type = if (c.status >= 500) "server_error" else "invalid_request_error"
    return c.json && (c.code == word || type == word)
}

private fun retry(c: Case): Lift =
    c.retryAfterS?.let { Lift.At(c.answeredAt.plusSeconds(it), true, LiftSource.RETRY_AFTER) } ?: Lift.Unstated

/** The rows about the request itself. */
private fun requestRow(c: Case, words: ProviderWords?): FailureCause.Provider = when (c.status) {
    400 -> badRequestRow(c, words)
    401 -> FailureCause.Provider.AuthRejected(c.owner, words)
    402 -> FailureCause.Provider.NeedsPayment(Lift.Unstated, false, words)
    403 -> FailureCause.Provider.Forbidden(words)
    404 -> if (names(c, "model_not_found")) {
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
        names(c, "context_length_exceeded") || overflow != null ->
            FailureCause.Provider.TooLong(overflow?.limit, false, words)
        names(c, "model_not_found") -> FailureCause.Provider.ModelMissing(modelNamed(c), words)
        POLICY.any { names(c, it) } -> FailureCause.Provider.Refused(RefusedBy.POLICY, words)
        else -> FailureCause.Provider.Rejected(false, words)
    }
}

/** The model this reply's own body named, which only its JSON carries. */
private fun modelNamed(c: Case): String = (c.said as? Said.Model)?.name?.takeIf { c.json }.orEmpty()

/** The rows about the provider's own side; a capacity code is an overload on any 5xx. */
private fun serverRow(c: Case, words: ProviderWords?): FailureCause.Provider = when {
    c.status == 503 || CAPACITY.any { names(c, it) } -> FailureCause.Provider.Overloaded(retry(c), false, words)
    c.status == 500 -> FailureCause.Provider.ServerError(ServerFault.INTERNAL, words)
    c.status == 502 -> FailureCause.Provider.ServerError(ServerFault.BAD_GATEWAY, words)
    c.status == 504 -> FailureCause.Provider.ServerError(ServerFault.GATEWAY_TIMEOUT, words)
    else -> FailureCause.Provider.Unrecognized(false, words)
}

/** final-design.md section 7, OpenAI Responses: the size refusal after a 1009 close, stale reasoning, the Chat rows. */
private fun expected(c: Case): FailureCause.Provider {
    val chat = chatRow(c)
    val clientError = c.status in 400..499
    return when {
        c.refusedOverWebSocket && clientError && c.status != 429 && chat !is FailureCause.Provider.TooLong ->
            FailureCause.Provider.TooLong(null, false, null)
        clientError && c.body.contains("encrypted_content", ignoreCase = true) ->
            FailureCause.Provider.Rejected(true, ProviderWordsFilter.of(if (c.json) c.said.text else c.body))
        else -> chat
    }
}

/** The shapes a failed round arrives in: response.failed's error object, the flat error event, an error event
 *  wrapping an error object, and a proxy's error given as a plain string (DR-109), which carries no code. */
private fun failedEvent(random: Random, code: String, message: String): Pair<JsonObject, String> =
    when (random.nextInt(4)) {
        0 -> buildJsonObject {
            put("type", "response.failed")
            putJsonObject("response") {
                put("status", "failed")
                putJsonObject("error") {
                    if (code.isNotEmpty()) put("code", code)
                    put("message", message)
                }
            }
        } to code
        1 -> buildJsonObject {
            put("type", "error")
            if (code.isNotEmpty()) put("code", code)
            put("message", message)
            put("param", JsonPrimitive(null as String?))
        } to code
        2 -> buildJsonObject {
            put("type", "error")
            putJsonObject("error") {
                put("type", "invalid_request_error")
                if (code.isNotEmpty()) put("code", code)
                put("message", message)
            }
        } to code.ifEmpty { "invalid_request_error" }
        else -> buildJsonObject {
            put("type", "error")
            put("error", message)
        } to ""
    }

/** The Responses table's code rows, and the Chat rows' codes at the status OpenAI answers each with. */
private fun byCode(code: String, message: String, owner: LoginOwner): FailureCause.Provider {
    val words = ProviderWordsFilter.of(message)
    return when (code) {
        "server_error" -> FailureCause.Provider.ServerError(ServerFault.INTERNAL, words)
        "rate_limit_exceeded" -> FailureCause.Provider.Limited(Lift.Unstated, null, words)
        "insufficient_quota" -> FailureCause.Provider.NeedsPayment(Lift.Unstated, false, words)
        "invalid_api_key" -> FailureCause.Provider.AuthRejected(owner, words)
        else -> bySizeOrCapacity(code, message, words)
    }
}

/** The rows an overflow and a capacity code take, and what a code no row names reads as. An error with NO code at
 *  all says an overflow only in its own words (V4-164); anything else it says is no row's. */
private fun bySizeOrCapacity(code: String, message: String, words: ProviderWords?): FailureCause.Provider = when {
    code == "context_length_exceeded" -> FailureCause.Provider.TooLong(limitOf(message), false, words)
    code == "invalid_prompt" || code == "invalid_request_error" -> FailureCause.Provider.Rejected(false, words)
    code == "server_is_overloaded" || code == "slow_down" ->
        FailureCause.Provider.Overloaded(Lift.Unstated, false, words)
    code.isEmpty() && limitOf(message) != null -> FailureCause.Provider.TooLong(limitOf(message), false, words)
    else -> FailureCause.Provider.Unrecognized(false, words)
}

private fun limitOf(message: String): Long? =
    Regex("maximum context length is (\\d+)").find(message)?.groupValues?.get(1)?.toLong()

class ResponsesRepliesPropertyTest {
    @Test
    fun `every Responses HTTP reply reads by the Chat rows, stale reasoning and the size refusal after a 1009 close`() {
        val random = Random(321)
        repeat(RUNS) {
            val c = case(random, TABLE_STATUSES, CODES)
            assertEquals(expected(c), ResponsesReplies.http(c.reply()), c.toString())
        }
    }

    @Test
    fun `every response failed and error event is read by its code`() {
        val random = Random(322)
        val codes = EVENT_CODES + UNKNOWN_CODES
        repeat(RUNS) {
            val owner = LoginOwner.entries.random(random)
            val code = codes.random(random)
            val message = when (code) {
                "context_length_exceeded" ->
                    "This model's maximum context length is ${random.nextLong(9_000, 900_000)}."
                else -> PLAIN.random(random)
            }
            val (event, readAs) = failedEvent(random, code, message)
            assertEquals(
                byCode(readAs, message, owner),
                ResponsesReplies.event(FailedEvent(ResponsesReplies.FAILED_EVENT, event, owner)),
                event.toString(),
            )
        }
    }

    @Test
    fun `an incomplete answer reads as Refused(FILTER) for its content filter, and a refusal part as Refused(MODEL)`() {
        val random = Random(323)
        repeat(RUNS) {
            val owner = LoginOwner.entries.random(random)
            val reason = listOf("content_filter", "interrupted", "vendor_reason").random(random)
            val incomplete = buildJsonObject { put("reason", reason) }
            assertEquals(
                if (reason == "content_filter") {
                    FailureCause.Provider.Refused(RefusedBy.FILTER)
                } else {
                    FailureCause.Provider.Unrecognized(false)
                },
                ResponsesReplies.event(FailedEvent(ResponsesReplies.INCOMPLETE_EVENT, incomplete, owner)),
                reason,
            )
            val refusal = PLAIN.random(random)
            assertEquals(
                FailureCause.Provider.Refused(RefusedBy.MODEL, ProviderWordsFilter.of(refusal)),
                ResponsesReplies.event(
                    FailedEvent(ResponsesReplies.REFUSAL_PART, buildJsonObject { put("refusal", refusal) }, owner),
                ),
            )
        }
    }

    @Test
    fun `a reply no table names is Unrecognized, with clientError set for a 4xx`() {
        val random = Random(324)
        repeat(RUNS) {
            val c = case(random, UNNAMED_STATUSES, UNKNOWN_CODES).let {
                it.copy(
                    said = Said.Plain(PLAIN.random(random)),
                    shape = it.shape.copy(refusedOverWebSocket = false),
                )
            }
            val words = ProviderWordsFilter.of(if (c.json) c.said.text else c.body)
            assertEquals(
                FailureCause.Provider.Unrecognized(c.status in 400..499, words),
                ResponsesReplies.http(c.reply()),
                c.toString(),
            )
        }
    }

    @Test
    fun `the same error reads as the same kind as an HTTP body and as an in-band event`() {
        val random = Random(325)
        val statusOf = mapOf(
            "context_length_exceeded" to 400, "invalid_prompt" to 400, "content_policy_violation" to 400,
            "model_not_found" to 404, "invalid_api_key" to 401, "insufficient_quota" to 429,
            "rate_limit_exceeded" to 429, "server_error" to 500, "server_is_overloaded" to 503,
        )
        repeat(RUNS) {
            val (code, status) = statusOf.entries.random(random).toPair()
            val c = case(random, listOf(status), listOf(code))
                .let { it.copy(retryAfterS = null, shape = it.shape.copy(json = true, refusedOverWebSocket = false)) }
                .let { if (it.said is Said.Stale) it.copy(said = Said.Plain(PLAIN.random(random))) else it }
            val event = buildJsonObject {
                put("type", "response.failed")
                putJsonObject("response") { put("error", c.error.getValue("error")) }
            }
            assertEquals(expected(c), ResponsesReplies.http(c.reply()), c.toString())
            assertEquals(
                expected(c),
                ResponsesReplies.event(FailedEvent(ResponsesReplies.FAILED_EVENT, event, c.owner)),
                c.toString(),
            )
        }
    }
}
