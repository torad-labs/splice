// NEW: Oct 10, 2026 review — which TTL a prompt-cache write was held at decides its price, so the passthrough
// dialect reads Anthropic's per-TTL breakdown and not only the write total. Anthropic charges an hourly write
// 2x input against the five-minute write's 1.25x, and Moonshot K3 6 against 3, so collapsing the two charged
// every hourly write the cheaper rate and a budget stayed open on spending it had already done.
//
// Its own file, with its own sink, because PassthroughStreamTranslatorTest sits at detekt's LargeClass limit.
package splice.dialect.anthropic

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.TurnOutcome
import splice.upstream.sse.WireSink

/** Accepts the wire without retaining it: these tests read the outcome's usage only. */
private class WriteSink : WireSink {
    private var blocks = 0
    override suspend fun openText(): WireBlockIndex = WireBlockIndex(blocks++)
    override suspend fun openThinking(): WireBlockIndex = WireBlockIndex(blocks++)
    override suspend fun openTool(id: String, name: String): WireBlockIndex = WireBlockIndex(blocks++)
    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex = WireBlockIndex(blocks++)
    override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
    override suspend fun closeBlock(index: WireBlockIndex) = Unit
    override suspend fun closeAll() = Unit
    override suspend fun addTextBlock(text: String) = Unit
    override suspend fun addRedactedThinking(data: String) = Unit
}

private fun frame(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

private val END = listOf(
    frame("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}"""),
    frame("""{"type":"message_stop"}"""),
)

class PassthroughCacheWriteTtlTest {

    /** One turn whose message_start carries [usage] as its usage object. */
    private suspend fun usageOf(usage: String): TurnOutcome.Success = PassthroughStreamTranslator(
        PassthroughTurnContext({ false }, { null }, 180_000, 900_000),
        PassthroughQuirks("synthetic"),
    ).driveTurn(
        (listOf(frame("""{"type":"message_start","message":{"usage":$usage}}""")) + END).asFlow(),
        WriteSink(),
    ) as TurnOutcome.Success

    @Test
    fun `the hourly share is read from the breakdown even when the flat total wins`() = runTest {
        // Anthropic sends both, and only the breakdown says which TTL the tokens were written at.
        val s = usageOf(
            """{"input_tokens":10,"cache_creation_input_tokens":40,""" +
                """"cache_creation":{"ephemeral_5m_input_tokens":25,"ephemeral_1h_input_tokens":15}}""",
        )

        assertEquals(40, s.usage.cacheWriteTokens, "the flat total, not the breakdown's sum")
        assertEquals(15, s.usage.cacheWriteHourlyTokens, "and the other 25 is the five-minute write")
    }

    @Test
    fun `a flat total with no breakdown reports no hourly share`() = runTest {
        // No TTL reported is the whole write bucket at the five-minute rate, which is what every turn
        // did before the split: no such turn changes price.
        val s = usageOf("""{"input_tokens":10,"cache_creation_input_tokens":40}""")

        assertEquals(40, s.usage.cacheWriteTokens)
        assertEquals(0, s.usage.cacheWriteHourlyTokens)
    }

    @Test
    fun `an hourly bucket larger than the total is held to the total`() = runTest {
        // A backend whose breakdown disagrees with its own total cannot make the hourly share bill
        // tokens the write bucket does not contain.
        val s = usageOf(
            """{"input_tokens":10,"cache_creation_input_tokens":12,""" +
                """"cache_creation":{"ephemeral_1h_input_tokens":99}}""",
        )

        assertEquals(12, s.usage.cacheWriteTokens)
        assertEquals(12, s.usage.cacheWriteHourlyTokens)
    }
}
