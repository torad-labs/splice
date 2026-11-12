// NEW: failure-handling slice 3. A local model server's own answers, held over generated replies: a model still
// loading is an overload that says so, its context-size refusal is an overflow with the window it names, and a model
// it does not hold is a missing model. Every other answer a local runtime sends is the Chat standard's row
// (final-design.md section 12, D1's second property).
package splice.provider.openai.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWordsFilter
import splice.dialect.chat.failure.ChatReplies
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant
import kotlin.random.Random

private const val RUNS = 2_000

private val NEVER_SENT = SentKey { _, _ -> false }

/** llama-server's 503 while the weights load (server-http.cpp:258), four glml53 journal lines on Oct 20 to 26. */
private const val LOADING_SAID = "Loading model"

/** Its decode failure when every slot's share of one context pool is spent (server-context.cpp:3610). */
private const val DECODE_SAID = "Context size has been exceeded."

/** Ollama's answer for a model it does not hold yet. */
private val PULL_FIRST = listOf(
    """model "qwen3:32b" not found, try pulling it first""",
    """model 'deepseek-v4' not found, try pulling it first""",
)

/** Answers the Chat standard already reads. */
private val STANDARD = listOf(
    "invalid_request_error" to "Invalid value for 'temperature'",
    "server_error" to "The server had an error while processing your request",
    "not_found_error" to "File not found",
)

/** llama-server writes its HTTP-equivalent status as a numeric code (format_error_response). */
private fun localBody(code: Int, type: String, message: String, promptTokens: Long? = null, window: Long? = null) =
    buildJsonObject {
        putJsonObject("error") {
            put("code", code)
            put("message", message)
            put("type", type)
            promptTokens?.let { put("n_prompt_tokens", it) }
            window?.let { put("n_ctx", it) }
        }
    }.toString()

private fun reply(status: Int, body: String, at: Instant): FailedReply =
    FailedReply(status, emptyMap(), body, LoginOwner.SPLICE, NEVER_SENT, at)

private fun answeredAt(random: Random): Instant =
    Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000))

class LocalRuntimeRepliesPropertyTest {
    @Test
    fun `a model still loading is an overload that says the server is still loading it`() {
        val random = Random(371)
        repeat(RUNS) {
            val body = localBody(503, "unavailable_error", LOADING_SAID)
            val expected = FailureCause.Provider.Overloaded(
                Lift.Unstated,
                loading = true,
                ProviderWordsFilter.of(LOADING_SAID),
            )

            assertEquals(expected, LocalRuntimeReplies.http(reply(503, body, answeredAt(random))), body)
            assertEquals(
                expected,
                LocalRuntimeReplies.event(
                    FailedEvent(ChatReplies.ERROR_CHUNK, parsed(body), LoginOwner.entries.random(random)),
                ),
                body,
            )
        }
    }

    @Test
    fun `a context-size refusal is an overflow, carrying the window the server named`() {
        val random = Random(372)
        repeat(RUNS) {
            val window = random.nextLong(2_048, 262_144)
            val prompt = window + random.nextLong(1, 40_000)
            val said = "the request exceeds the available context size. try increasing the context size"
            val body = localBody(400, "exceed_context_size_error", said, promptTokens = prompt, window = window)

            assertEquals(
                FailureCause.Provider.TooLong(window, whileAnswering = false, ProviderWordsFilter.of(said)),
                LocalRuntimeReplies.http(reply(400, body, answeredAt(random))),
                body,
            )
        }
    }

    @Test
    fun `a model the runtime does not hold is a missing model, named as the runtime named it`() {
        val random = Random(373)
        repeat(RUNS) {
            val said = PULL_FIRST.random(random)
            val body = localBody(404, "not_found_error", said)
            val named = said.substringAfter('"', "").substringBefore('"', "")
                .ifEmpty { said.substringAfter('\'', "").substringBefore('\'', "") }

            assertEquals(
                FailureCause.Provider.ModelMissing(named, ProviderWordsFilter.of(said)),
                LocalRuntimeReplies.http(reply(404, body, answeredAt(random))),
                body,
            )
        }
    }

    @Test
    fun `every other answer, the shared context pool running out included, is the Chat standard's`() {
        val random = Random(374)
        repeat(RUNS) {
            val (type, said) = STANDARD.random(random)
            val status = listOf(400, 404, 500, 503).random(random)
            val body = localBody(status, type, said)

            assertNull(LocalRuntimeReplies.http(reply(status, body, answeredAt(random))), body)
            assertNull(
                LocalRuntimeReplies.event(FailedEvent(ChatReplies.ERROR_CHUNK, parsed(body), LoginOwner.SPLICE)),
                body,
            )
            // The KV cache of a shared server filling up is the server's own failure, not this request outgrowing
            // its window: the same bytes fit when another conversation on that server ends, so it is no overflow.
            val decode = localBody(500, "server_error", DECODE_SAID)
            assertNull(LocalRuntimeReplies.http(reply(500, decode, answeredAt(random))), decode)
        }
    }
}

/** The event payload a body arrives as in band: the same object, as the stream carried it. */
private fun parsed(body: String) =
    kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
