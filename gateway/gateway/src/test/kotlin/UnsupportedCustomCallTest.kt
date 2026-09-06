import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.round.RoundStrategy
import splice.gateway.round.RunnerSignals
import splice.spi.WireSink

class UnsupportedCustomCallTest {
    @Test
    fun `unarmed custom call fails honestly without erasing vendor usage`() = runTest {
        val billed = Usage(80, 7, 20, 3)
        val finishes = mutableListOf<TurnOutcome>()
        RoundStrategy(
            key = "test",
            log = {},
            emitter = UnusedSink(),
            signals = RunnerSignals(),
            postRoundToSink = { _, _ -> error("single round must not buffer") },
            postRound = {
                TurnOutcome.Success(
                    hasToolUse = false,
                    incomplete = false,
                    usage = billed,
                    customCalls = listOf(GatewayCustomCall("custom-1", "unknown", "", JsonObject(emptyMap()))),
                )
            },
            finish = { finishes += it },
        ).run(JsonObject(emptyMap()), fold = null, reanchor = null)
        assertEquals(1, finishes.size)
        val failure = finishes.single() as TurnOutcome.Failure
        assertEquals(ErrorType.INVALID_REQUEST, failure.type)
        assertEquals(billed, failure.salvagedUsage)
    }

    private class UnusedSink : WireSink {
        override suspend fun openText(): WireBlockIndex = error("unexpected sink write")
        override suspend fun openThinking(): WireBlockIndex = error("unexpected sink write")
        override suspend fun openTool(id: String, name: String): WireBlockIndex = error("unexpected sink write")
        override suspend fun textDelta(index: WireBlockIndex, text: String) = Unit
        override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) = Unit
        override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) = Unit
        override suspend fun closeBlock(index: WireBlockIndex) = Unit
        override suspend fun closeAll() = Unit
        override suspend fun addTextBlock(text: String) = Unit
        override suspend fun addRedactedThinking(data: String) = Unit
    }
}
