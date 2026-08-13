// NEW: the chat translator's USAGE reads, split out of ChatStreamTranslatorTest (detekt
// LargeClass) when CX-18 added the alias cases. Usage is not cosmetic on this dialect:
// used_percentage drives Claude Code's auto-compaction trigger, so a bucket read as zero either
// suppresses compaction forever or fires it constantly.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.turn.TurnOutcome

class ChatUsageTest {

    @Test
    fun `usage captures cached tokens from prompt_tokens_details`() = runTest {
        val s = driveEvents(
            ev("""{"choices":[{"delta":{"content":"x"},"finish_reason":null}]}"""),
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":80}}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(100, s.usage.inputTokens)
        assertEquals(5, s.usage.outputTokens)
        assertEquals(80, s.usage.cachedTokens)
    }

    // CX-18: OpenRouter's Responses-shaped routes and several OpenAI-compatible servers report the
    // two main buckets as input_tokens/output_tokens. Reading only prompt_/completion_ landed those
    // turns with zero usage, which drives used_percentage and the context-window HUD.
    @Test
    fun `usage reads the input_tokens output_tokens alias shape`() = runTest {
        val s = driveEvents(
            ev("""{"choices":[{"delta":{"content":"x"},"finish_reason":null}]}"""),
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"input_tokens":70,"output_tokens":9}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(70, s.usage.inputTokens)
        assertEquals(9, s.usage.outputTokens)
    }

    @Test
    fun `the canonical prompt_tokens spelling still wins over the alias`() = runTest {
        // A backend emitting BOTH must not have the alias override the standard field.
        val s = driveEvents(
            ev("""{"choices":[{"delta":{"content":"x"},"finish_reason":null}]}"""),
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":11,"completion_tokens":3,"input_tokens":999,"output_tokens":999}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(11, s.usage.inputTokens)
        assertEquals(3, s.usage.outputTokens)
    }

    @Test
    fun `usage cached tokens fall back to flat cached_tokens`() = runTest {
        val s = driveEvents(
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"cached_tokens":40}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(40, s.usage.cachedTokens)
    }

    @Test
    fun `usage cached tokens fall back to deepseek prompt_cache_hit_tokens`() = runTest {
        val s = driveEvents(
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_cache_hit_tokens":25}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(25, s.usage.cachedTokens)
    }

    @Test
    fun `usage cached tokens absent defaults to zero`() = runTest {
        val s = driveEvents(
            ev(
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5}}""",
            ),
        ) as TurnOutcome.Success
        assertEquals(0, s.usage.cachedTokens)
    }
}
