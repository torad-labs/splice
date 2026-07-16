// PORT-OF: usage pins from server/test/{control-server,codex-proxy}.test.mjs @ 4ca99f7 —
// warn table (ratelimit priority, critical bounds, tokens5h fallback, none), payload aliases
// + non-standard context fields, output clamp with exact log line, 5h window pruning,
// ratelimit header parsing.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.usage.RateLimitState
import splice.gateway.usage.TurnUsage
import splice.gateway.usage.UsageStore
import splice.gateway.usage.buildUsagePayload
import splice.gateway.usage.cacheLogLine
import splice.core.usage.computeUsageWarn
import splice.gateway.usage.makeOutputClamp
import java.nio.file.Path

private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

class UsageTest {

    @Test
    fun `warn - ratelimit signal has priority and bounds`() {
        val ninety = computeUsageWarn(ratelimit = RateLimitState(1000, 100, "6m0s"), warnPct = 80)
        assertEquals("warn", ninety.level)
        assertEquals(90, ninety.pct)
        assertEquals("ratelimit", ninety.source)
        assertEquals("6m0s", ninety.reset)
        assertEquals("critical", computeUsageWarn(ratelimit = RateLimitState(1000, 0, null)).level)
        assertEquals("critical", computeUsageWarn(ratelimit = RateLimitState(1000, 15, null)).level)
        assertEquals("ok", computeUsageWarn(ratelimit = RateLimitState(1000, 500, null)).level)
        // ratelimit present but incomplete -> falls through to tokens5h
        assertEquals(
            "tokens5h",
            computeUsageWarn(
                outputTokens5h = 10,
                ratelimit = RateLimitState(null, null, null),
                warnTokens5h = 100,
            ).source,
        )
    }

    @Test
    fun `warn - tokens5h fallback thresholds and none`() {
        assertEquals("ok", computeUsageWarn(outputTokens5h = 10, warnTokens5h = 100).level)
        assertEquals("warn", computeUsageWarn(outputTokens5h = 85, warnTokens5h = 100).level)
        assertEquals("critical", computeUsageWarn(outputTokens5h = 120, warnTokens5h = 100).level)
        assertEquals(100, computeUsageWarn(outputTokens5h = 120, warnTokens5h = 100).pct)
        val none = computeUsageWarn(outputTokens5h = 999_999, warnTokens5h = 0)
        assertEquals("ok", none.level)
        assertEquals("none", none.source)
    }

    @Test
    fun `payload - aliases, cached detail, and the non-standard context fields`() {
        val usage = TurnUsage.from(
            obj("""{"prompt_tokens":100,"completion_tokens":7,"input_tokens_details":{"cached_tokens":60}}"""),
        )
        assertEquals(100, usage.inputTokens)
        assertEquals(7, usage.outputTokens)
        assertEquals(60, usage.cacheReadInputTokens)
        val payload = buildUsagePayload(usage, contextWindow = 272_000)
        assertEquals("100", payload["input_tokens"]?.jsonPrimitive?.content)
        assertEquals("272000", payload["context_window"]?.jsonPrimitive?.content)
        assertEquals("272000", payload["context_window_size"]?.jsonPrimitive?.content)
        val pct = payload["used_percentage"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
        assertTrue(pct > 0.058 && pct < 0.06, "used pct = $pct") // 160/272000*100
        assertNull(buildUsagePayload(usage, contextWindow = null)["context_window"])
    }

    @Test
    fun `cache log line format is exact`() {
        val line = cacheLogLine(
            "codex-proxy",
            "gpt-5.6-sol",
            obj("""{"input_tokens":200,"input_tokens_details":{"cached_tokens":150},"output_tokens":9}"""),
            compact = true,
        )
        assertEquals(
            "[codex-proxy] cache: input=200 cached=150 hit=75% output=9 compact model=gpt-5.6-sol\n",
            line,
        )
    }

    @Test
    fun `output clamp - over clamps with the log line, under passes, null max passes`() {
        val logs = mutableListOf<String>()
        val clamp = makeOutputClamp(32_000, compact = false, headTag = "codex-proxy", log = { logs.add(it) })
        assertEquals(32_000, clamp(200_000))
        assertTrue(logs.single().contains("output_tokens 200000 > client max_tokens 32000 compact=false"))
        assertEquals(10, clamp(10))
        val noMax = makeOutputClamp(null, compact = false, headTag = "t", log = { logs.add(it) })
        assertEquals(999_999, noMax(999_999))
    }

    @Test
    fun `usage store - 5h window prunes, sums, and ratelimit round-trips`(@TempDir tmp: Path) {
        var now = 10_000_000_000L
        val store = UsageStore(tmp.resolve("codex-usage.json"), tmp.resolve("codex-ratelimit.json"), clock = { now })
        store.appendOutputTokens(100)
        now += 1_000
        store.appendOutputTokens(50)
        // jump past the 5h window: the next append prunes both old entries
        now += 5 * 60 * 60 * 1000L
        store.appendOutputTokens(7)
        val state = store.readState()
        assertEquals(1, state.entries)
        assertEquals(7, state.outputTokens5h)
        assertEquals(5, state.windowHours)

        store.persistRateLimit { name ->
            mapOf(
                "x-ratelimit-limit-tokens" to "5000",
                "x-ratelimit-remaining-tokens" to "1200",
                "x-ratelimit-reset-tokens" to "6m0s",
            )[name]
        }
        val rl = store.readRateLimit()!!
        assertEquals(5000, rl.limitTokens)
        assertEquals(1200, rl.remainingTokens)
        assertEquals("6m0s", rl.resetTokens)
        // absent limit header -> no-op (file unchanged)
        store.persistRateLimit { null }
        assertEquals(5000, store.readRateLimit()!!.limitTokens)
    }
}
