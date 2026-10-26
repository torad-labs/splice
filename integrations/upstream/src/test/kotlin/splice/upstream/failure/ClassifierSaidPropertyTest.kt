// NEW: failure-handling slice 2 (2026-10-26). The provider's words on a cause are its own message field, through
// ProviderWordsFilter, whatever shape its body takes and whatever the status decides. The sentences the classifier adds
// for the log (a quota suffix, llama-server's explanation, the gateway restatement) are splice's, so none of them is
// ever what the provider said.
package splice.upstream.failure

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.ProviderWordsFilter
import kotlin.random.Random

private const val RUNS = 600

private val SUBJECTS = listOf("Your account", "The model", "This request", "The organization", "Your plan", "This key")
private val PREDICATES = listOf(
    "has no remaining credit", "is not allowed to use this model", "exceeded its monthly allowance",
    "was stopped by the content filter", "is over capacity", "is too long for the context window",
    "was rate limited", "is not authorized", "hit an internal error", "named an unknown tool",
)

/** Sentences a person may not read whole: each is cut, or loses a part, by the filter. */
private val NOISE = listOf(
    "Visit https://example.com/billing for details.",
    "Please retry later.",
    "Request id req_01HZX9QW3E5R7T9Y1U3I5O7P9A failed (request_id: 49d5cc56-b2b7-42ed-8485-ac06076e79d4).",
    "The upstream gateway at 10.0.4.17:8443 answered.",
    "Key sk-test0****************Zq9x was rejected.",
    "Field messages.12.content.0.text is invalid.",
)

private val STATUSES = listOf(null, 400, 401, 402, 403, 404, 409, 413, 422, 429, 500, 502, 503, 504)

/** The provider's own message [m], in each shape a provider's error body puts it. */
private val SHAPES: List<(String) -> String> = listOf(
    { m -> """{"error":{"message":${json(m)},"type":"invalid_request_error"}}""" },
    { m -> """{"error":{"message":${json(m)},"type":"x","plan_type":"pro","resets_in_seconds":3600}}""" },
    { m -> """{"type":"error","error":{"type":"rate_limit_error","message":${json(m)}}}""" },
    { m -> """{"code":"personal-team-blocked:spending-limit","error":${json(m)}}""" },
    { m -> """{"message":${json(m)}}""" },
    { m -> """{"detail":${json(m)}}""" },
    { m -> m },
)

private fun json(text: String): String = JsonPrimitive(text).toString()

private fun Random.pick(from: List<String>): String = from[nextInt(from.size)]

private fun Random.message(): String {
    val sentences = (0..nextInt(3)).map {
        if (nextInt(3) == 0) pick(NOISE) else "${pick(SUBJECTS)} ${pick(PREDICATES)}."
    }
    return sentences.joinToString(" ")
}

class ClassifierSaidPropertyTest {
    @Test
    fun `an HTTP failure's words are the provider's own message field through the filter, in every shape`() {
        val random = Random(21)
        repeat(RUNS) {
            val message = random.message()
            val body = SHAPES[random.nextInt(SHAPES.size)](message)
            val status = STATUSES[random.nextInt(STATUSES.size)]

            val said = UpstreamFailureClassifier.classify(FailureSource.HTTP, body, status).cause.said

            assertEquals(ProviderWordsFilter.of(message), said, "status $status, body $body")
        }
    }

    @Test
    fun `an in-band failure's words are its own text through the filter`() {
        val random = Random(22)
        repeat(RUNS) {
            val text = random.message()
            val code = listOf(null, "server_error", "rate_limit_exceeded", "context_length_exceeded").let {
                it[random.nextInt(it.size)]
            }

            val said = UpstreamFailureClassifier.classify(FailureSource.SSE, text, code = code).cause.said

            assertEquals(ProviderWordsFilter.of(text), said, "code $code, text $text")
        }
    }

    @Test
    fun `a body with no message field of the provider's, or a gateway page, says nothing`() {
        listOf(
            """{"error":{"type":"server_error","code":"boom"}}""",
            """{"error":{"message":null}}""",
            """[{"message":"bad"}]""",
            """{"status":503}""",
            "<html><head><title>502 Bad Gateway</title></head><body>cloudflare</body></html>",
            "",
        ).forEach { body ->
            listOf(null, 400, 429, 500, 502).forEach { status ->
                assertNull(UpstreamFailureClassifier.classify(FailureSource.HTTP, body, status).cause.said, body)
            }
        }
    }

    @Test
    fun `the quota suffix the log reads is never part of what the provider said`() {
        val body = """{"error":{"message":"The usage limit has been reached","type":"usage_limit_reached",""" +
            """"plan_type":"pro","resets_in_seconds":518400}}"""

        val read = UpstreamFailureClassifier.classify(FailureSource.HTTP, body, 429)

        assertEquals("The usage limit has been reached (plan=pro, resets in 6d 0h)", read.message)
        assertEquals("The usage limit has been reached.", read.cause.said?.text)
    }
}
