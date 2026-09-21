package splice.head.compaction

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.compaction.CompactionConfig
import splice.core.compaction.CompactionInstructions
import splice.core.compaction.CompactionModelConfig
import splice.core.compaction.CompactionProjectConfig
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.head.compact.CompactStats
import splice.head.pipeline.StreamCompact
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class CompactionTailTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `ordinary turns neither resolve a project nor select custom text`() {
        val lookups = AtomicInteger()
        val resolver = CompactionTail(
            CompactionInstructions(CompactionConfig(instructions = "global"), tmp),
            projectFor = {
                lookups.incrementAndGet()
                tmp
            },
        )

        assertNull(resolver.resolve(compact = false, wireModel = "astra", sessionId = "session"))
        assertEquals(0, lookups.get())
    }

    @Test
    fun `confirmed compaction resolves the current project and wire model on every request`() {
        val one = tmp.resolve("one")
        val two = tmp.resolve("two")
        var project = one
        val resolver = CompactionTail(
            CompactionInstructions(
                CompactionConfig(
                    instructions = "global",
                    model = listOf(CompactionModelConfig("astra", instructions = "astra")),
                    project = listOf(
                        CompactionProjectConfig(one.toString(), "astra", instructions = "one-astra"),
                        CompactionProjectConfig(two.toString(), "sol", instructions = "two-sol"),
                    ),
                ),
                tmp,
            ),
            projectFor = { project },
        )

        assertEquals("one-astra", resolver.resolve(true, "astra", "session")?.text)
        project = two
        assertEquals("two-sol", resolver.resolve(true, "sol", "session")?.text)
        assertEquals("global", resolver.resolve(true, "other", "session")?.text)
    }

    @Test
    fun `unknown session resolves global directly`() {
        val resolver = CompactionTail(
            CompactionInstructions(
                CompactionConfig(
                    instructions = "global",
                    model = listOf(CompactionModelConfig("astra", instructions = "model")),
                ),
                tmp,
            ),
            projectFor = { null },
        )

        assertEquals("global", resolver.resolve(true, "astra", null)?.text)
        assertEquals("global", resolver.resolve(true, "other", "unknown")?.text)
    }

    @Test
    fun `compact statistics retain the effective text and source`() {
        val stats = CompactStats(tmp.resolve("compact.jsonl"))
        val meta = TurnMeta(
            compact = true,
            showReasoning = ReasoningDisplay.OFF,
            stream = true,
            originalModel = "alias--astra",
            upstreamModel = "astra",
            clientMaxTokens = null,
            effort = "high",
            summary = null,
            budgetTokens = null,
            compactionInstructions = "retain decisions",
            compactionInstructionsSource = "model:astra",
        )

        StreamCompact(stats).record(meta, "model_text", elapsedMs = 7, chars = 16)
        val row = stats.read().tail.single()

        assertEquals("retain decisions", row.getValue("instructions").jsonPrimitive.content)
        assertEquals("model:astra", row.getValue("instructions_source").jsonPrimitive.content)
    }
}
