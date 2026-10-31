// The prompt-cache identity (splice-<sha256 of the first user text>) is stable: empty separators, ignored blocks
// and malformed UTF-16 hash the way they always did, and concurrent builds keep their digests apart.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.ReasoningDisplay
import splice.core.util.JsonWire
import splice.core.wire.AnthropicMessage
import splice.core.wire.AnthropicRequest
import splice.core.wire.ContentBlock
import splice.core.wire.TextBlock
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ResponsesCacheKeyIdentityTest {
    private val quirks = ResponsesQuirks(
        providerTag = "synthetic",
        backend = ResponsesBackendQuirks(
            promptCache = PromptCachePolicy(key = CacheKeyStrategy.FIRST_MESSAGE_HASH),
            sendClientMetadata = true,
        ),
        lite = ResponsesLiteQuirks(
            responsesLiteModelRegex = Regex("gpt-6"),
        ),
    )

    @Test
    fun `streamed identities preserve legacy empty separators excluded blocks and malformed UTF8`() {
        val seeds = listOf(
            emptyList(),
            listOf(""),
            listOf("", ""),
            listOf("", "alpha", "", ""),
            listOf("café 🧪\n\"\\slash", "\uD800", "\uDC00", "\uD800x\uDC00"),
            listOf("x".repeat(8_191) + "🧪é", "\uD800", "", "\uDC00" + "y".repeat(8_192)),
        )
        val ids = ResponsesStableIds()
        for (seed in seeds) {
            val ignored = ContentBlock.ThinkingBlock("synthetic non-text")
            val content = listOf(ignored) + seed.flatMap { listOf(TextBlock(it), ignored) }
            val body = AnthropicRequest(
                messages = listOf(
                    AnthropicMessage("assistant", listOf(TextBlock("not the user seed"))),
                    AnthropicMessage("user", content),
                    AnthropicMessage("user", listOf(TextBlock("not the first user"))),
                ),
            )
            val expected = legacyKey(seed)
            repeat(3) { assertEquals(expected, ids.stablePromptCacheKey(body)) }
            val built = ResponsesRequestBuilder(quirks).build(body, JsonObject(emptyMap()), options())
            assertEquals(expected, built.meta.scope.conversationKey)
            assertEquals(expected, built.req["prompt_cache_key"]?.let { JsonWire.string(it).trim('"') })
        }
        assertEquals(null, ids.stablePromptCacheKey(AnthropicRequest()))
    }

    @Test
    fun `concurrent preparation keeps each first-user digest independent`() {
        val ids = ResponsesStableIds()
        val builder = ResponsesRequestBuilder(quirks)
        val barrier = CyclicBarrier(4)
        Executors.newFixedThreadPool(4).use { workers ->
            val calls = (0 until 4).map { index ->
                workers.submit {
                    val text = "synthetic-$index café 🧪\uD800".repeat(1_000)
                    val body = AnthropicRequest(messages = listOf(AnthropicMessage("user", listOf(TextBlock(text)))))
                    val expected = legacyKey(listOf(text))
                    repeat(20) {
                        barrier.await(5, TimeUnit.SECONDS)
                        assertEquals(expected, ids.stablePromptCacheKey(body))
                        val built = builder.build(body, JsonObject(emptyMap()), options())
                        assertEquals(expected, built.meta.scope.conversationKey)
                    }
                }
            }
            calls.forEach { it.get(10, TimeUnit.SECONDS) }
        }
    }

    private fun legacyKey(text: List<String>): String? {
        val seed = text.joinToString("\n").ifEmpty { return null }
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return "splice-" + HexFormat.of().formatHex(digest, 0, 16)
    }

    private fun options() = BuildOptions(
        compact = false,
        models = ModelIds(
            original = "synthetic",
            upstream = "gpt-6.1-sol",
        ),
        reasoning = RequestedReasoning(
            effort = "high",
            summary = "detailed",
            display = ReasoningDisplay.TEXT,
        ),
        handoff = ReasoningHandoff(
            replay = InjectPriorReasoning(false),
            decode = { JsonObject(emptyMap()) },
        ),
    )
}
