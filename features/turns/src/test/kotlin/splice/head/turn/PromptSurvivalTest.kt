// Whether a system layer's text survived into the final request body, asked of the JSON tree.
package splice.head.turn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptSurvivalTest {
    private fun systemBody(system: String): JsonObject = buildJsonObject {
        put("model", "synthetic")
        put("system", system)
    }

    /** Layers with a real newline, a quote and a backslash: the characters a serialised comparison got wrong. */
    private fun appends(): List<String> = listOf(
        "First line of a synthetic layer.\nSecond line, after a real newline.\nThird.",
        """A synthetic layer that says "quoted" in the middle.""",
        """A synthetic layer with a backslash \ and a path C:\synthetic\dir in it.""",
        "A layer with a tab\tand a quote \" and a backslash \\ together.\nOn two lines.",
    )

    @Test
    fun `a carried layer reads as carried and a stripped one as deleted`() {
        appends().forEach { layer ->
            val carried = systemBody("A head preamble.\n\n$layer\n\nA trailing paragraph.")
            val stripped = systemBody("A head preamble.\n\nA trailing paragraph.")

            assertTrue(PromptSurvival.present(carried, layer), "a carried layer must read as carried: $layer")
            assertFalse(PromptSurvival.present(stripped, layer), "a stripped layer must read as deleted: $layer")
        }
    }

    @Test
    fun `the text is found wherever a string value holds it, not only at the top level`() {
        val layer = "A synthetic layer inside a content block."
        val nested = buildJsonObject {
            put("model", "synthetic")
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "system")
                            put(
                                "content",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "A preamble.\n\n$layer")
                                        },
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            )
        }

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
}
