// NEW: the layer-survived question asked of the tree — parity with the serialise-then-contains check
// it replaces, and the budget that rejects asking it of a serialised copy of the whole body.
package splice.head.turn

import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import splice.core.util.JsonWire
import java.lang.management.ManagementFactory

private const val WARMUPS = 10
private const val BLOCKS = 430

/** One body-sized String is what this replaces, so the budget sits far below the body and the test
 *  proves the old way exceeds it rather than only that the new way fits. */
private const val SURVIVAL_BUDGET = 200_000L

/** Exactly what TurnPrompts.survivedFinalBody did until this test existed: serialise the whole request
 *  body, and search it for the layer's text as the serialiser would have written it. Kept here as the
 *  parity reference, because an answer that changed would be a false landing in a turn's meta. */
private object SerialisedReference {
    fun present(body: JsonObject, text: String): Boolean =
        JsonWire.string(body).contains(JsonPrimitive(text).let(JsonWire::string).removeSurrounding("\""))
}

class PromptSurvivalTest {
    private val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        ?: error("JVM allocation counter is required")

    private fun systemBody(system: String): JsonObject = buildJsonObject {
        put("model", "synthetic")
        put("system", system)
    }

    /** The appends whose escaping broke the old comparison before its needle was escaped too: a real
     *  newline, a quote and a backslash. Each is checked present and absent, and the absent case is a
     *  strip layer having deleted it. */
    private fun appends(): List<String> = listOf(
        "First line of a synthetic layer.\nSecond line, after a real newline.\nThird.",
        """A synthetic layer that says "quoted" in the middle.""",
        """A synthetic layer with a backslash \ and a path C:\synthetic\dir in it.""",
        "A layer with a tab\tand a quote \" and a backslash \\ together.\nOn two lines.",
    )

    @Test
    fun `the tree walk answers what serialising the body answered, placed and stripped`() {
        appends().forEach { layer ->
            val carried = systemBody("A head preamble.\n\n$layer\n\nA trailing paragraph.")
            val stripped = systemBody("A head preamble.\n\nA trailing paragraph.")

            assertEquals(
                SerialisedReference.present(carried, layer),
                PromptSurvival.present(carried, layer),
                "placed parity for: $layer",
            )
            assertEquals(
                SerialisedReference.present(stripped, layer),
                PromptSurvival.present(stripped, layer),
                "stripped parity for: $layer",
            )
            assertTrue(PromptSurvival.present(carried, layer), "a carried layer must read as carried")
            assertFalse(PromptSurvival.present(stripped, layer), "a stripped layer must read as deleted")
        }
    }

    @Test
    fun `the text is found wherever a string value holds it, not only at the top level`() {
        val layer = "A synthetic layer inside a content block."
        val nested = buildJsonObject {
            put("model", "synthetic")
            put("messages", JsonArray(listOf(systemBlock("A preamble.\n\n$layer"))))
        }

        assertEquals(SerialisedReference.present(nested, layer), PromptSurvival.present(nested, layer))
        assertTrue(PromptSurvival.present(nested, layer))
        assertFalse(PromptSurvival.present(nested, "A paragraph this body never carried."))
    }

    @Test
    fun `a non-string value never matches, so a number or a key cannot stand in for a layer`() {
        val body = buildJsonObject {
            put("max_tokens", 8_000)
            put("synthetic_key", true)
        }

        assertFalse(PromptSurvival.present(body, "8000"))
        assertFalse(PromptSurvival.present(body, "synthetic_key"), "a KEY is not a value the wire carried")
    }

    @Test
    fun `asking the tree costs bounded scratch where serialising the body costs the body`(
        reporter: TestReporter,
    ) {
        bean.isThreadAllocatedMemoryEnabled = true
        val layer = "The layer whose survival is in question.\nOn two lines."
        val body = largeBody(layer)
        val size = JsonWire.byteSize(body)
        assertTrue(size > 1_000_000, "the fixture must be body-sized; it is $size bytes")

        val tree = measure("tree", reporter) { PromptSurvival.present(body, layer) }
        val serialised = measure("serialised", reporter) { SerialisedReference.present(body, layer) }

        assertTrue(tree.first, "the layer is in the fixture, so both ways must find it")
        assertTrue(serialised.first)
        assertTrue(
            serialised.second >= SURVIVAL_BUDGET,
            "the budget must reject serialising the body: serialised=${serialised.second}",
        )
        assertTrue(tree.second < SURVIVAL_BUDGET, "tree=${tree.second}; budget=$SURVIVAL_BUDGET")
    }

    private inline fun <T> measure(name: String, reporter: TestReporter, action: () -> T): Pair<T, Long> {
        repeat(WARMUPS) { val _ = action() }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val result = action()
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(thread == Thread.currentThread().threadId(), "the measured call must stay on this thread")
        reporter.publishEntry("survival_${name}_allocated_bytes", allocated.toString())
        return result to allocated
    }

    private fun systemBlock(text: String): JsonObject = buildJsonObject {
        put("role", "system")
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                ),
            ),
        )
    }

    /** The layer rides in the LAST block, so a walk that short-circuits early still pays the full
     *  traversal and the comparison is honest. */
    private fun largeBody(layer: String): JsonObject = buildJsonObject {
        put("model", "synthetic")
        put(
            "messages",
            JsonArray(
                List(BLOCKS) { index ->
                    buildJsonObject {
                        put("role", if (index % 2 == 0) "user" else "assistant")
                        put(
                            "content",
                            JsonArray(
                                List(6) { block ->
                                    val filler = "Synthetic turn $index block $block. ".repeat(14)
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", if (index == BLOCKS - 1 && block == 5) "$filler$layer" else filler)
                                    }
                                },
                            ),
                        )
                    }
                },
            ),
        )
    }
}
