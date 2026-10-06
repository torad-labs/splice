// NEW: per-build cache identity preserves the existing key and request bytes without prompt-sized scratch.
package splice.dialect.responses.request

import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import splice.core.perf.InputDigest
import splice.core.turn.ReasoningDisplay
import splice.core.util.JsonWire
import splice.core.wire.AnthropicMessage
import splice.core.wire.AnthropicRequest
import splice.core.wire.ContentBlock
import splice.core.wire.TextBlock
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ResponsesPreparationBudgetTest {
    private val quirks = ResponsesQuirks(
        providerTag = "synthetic",
        promptCache = PromptCachePolicy(key = CacheKeyStrategy.FIRST_MESSAGE_HASH),
        responsesLiteModelRegex = Regex("gpt-6"),
        sendClientMetadata = true,
    )

    @Test
    fun `large first user text does not allocate a prompt copy for each identity field`(reporter: TestReporter) {
        val text = "café 🧪 synthetic ".repeat(100_000)
        val body = AnthropicRequest(messages = listOf(AnthropicMessage("user", listOf(TextBlock(text)))))
        val raw = JsonObject(emptyMap())
        val ids = ResponsesStableIds()
        val builder = ResponsesRequestBuilder(quirks)
        val options = options()
        val legacy = measure { check(legacyKey(listOf(text)) != null) }
        val key = measure { check(ids.stablePromptCacheKey(body) != null) }
        val build = measure { check(builder.build(body, raw, options).meta.conversationKey != null) }
        val wire = measure { check(JsonWire.string(builder.build(body, raw, options).req).isNotEmpty()) }
        reporter.publishEntry("legacy_seed_allocated_bytes", legacy.first.toString())
        reporter.publishEntry("legacy_seed_thread_cpu_ns", legacy.second.toString())
        reportWireProof(builder.build(body, raw, options), text, wire, reporter)
        reporter.publishEntry("seed_utf8_bytes", JsonWire.byteSize(text).toString())
        reporter.publishEntry("key_allocated_bytes", key.first.toString())
        reporter.publishEntry("key_thread_cpu_ns", key.second.toString())
        reporter.publishEntry("build_allocated_bytes", build.first.toString())
        reporter.publishEntry("build_thread_cpu_ns", build.second.toString())
        // The same measured budget must reject the old whole-seed allocation, not just accept the new path.
        assertTrue(legacy.first >= 250_000, "legacy_seed_allocated_bytes=${legacy.first}")
        assertTrue(key.first < 250_000, "key_allocated_bytes=${key.first}")
        assertTrue(build.first < 500_000, "build_allocated_bytes=${build.first}")
        // A single UTF-16 result plus bounded metadata fits below this; the measured 9 MB staging path does not.
        val wireBudget = text.length * 3L + 250_000L
        assertTrue(wire.first < wireBudget, "build_and_wire_allocated_bytes=${wire.first}, budget=$wireBudget")
    }

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
            assertEquals(expected, built.meta.conversationKey)
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
                        assertEquals(expected, built.meta.conversationKey)
                    }
                }
            }
            calls.forEach { it.get(10, TimeUnit.SECONDS) }
        }
    }

    private fun reportWireProof(built: BuiltRequest, text: String, measured: Pair<Long, Long>, reporter: TestReporter) {
        val expected = built.req.toString()
        val actual = JsonWire.string(built.req)
        assertEquals(expected, actual, "the allocation saving must preserve every wire byte")
        assertEquals(legacyKey(listOf(text)), built.meta.conversationKey, "the prompt-cache identity stays unchanged")
        reporter.publishEntry(
            mapOf(
                "build_and_wire_allocated_bytes" to measured.first.toString(),
                "build_and_wire_thread_cpu_ns" to measured.second.toString(),
                "build_and_wire_body_bytes" to JsonWire.byteSize(actual).toString(),
                "build_and_wire_body_sha256" to InputDigest.hex(actual),
                "legacy_wire_body_sha256" to InputDigest.hex(expected),
            ),
        )
    }

    private fun legacyKey(text: List<String>): String? {
        val seed = text.joinToString("\n").ifEmpty { return null }
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return "splice-" + HexFormat.of().formatHex(digest, 0, 16)
    }

    private fun options() = BuildOptions(
        compact = false,
        originalModel = "synthetic",
        upstreamModel = "gpt-6.1-sol",
        configEffort = "high",
        configSummary = "detailed",
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = InjectPriorReasoning(false),
        decodeReasoningEnvelope = { JsonObject(emptyMap()) },
    )

    private fun measure(action: () -> Unit): Pair<Long, Long> {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported && bean.isCurrentThreadCpuTimeSupported)
        bean.isThreadAllocatedMemoryEnabled = true
        bean.isThreadCpuTimeEnabled = true
        repeat(64) { action() }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val cpu = bean.currentThreadCpuTime
        repeat(32) { action() }
        return (bean.getThreadAllocatedBytes(thread) - before) / 32 to (bean.currentThreadCpuTime - cpu) / 32
    }
}
