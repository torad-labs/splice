// A head's readable model names (ReadableIds): what Claude Desktop and its Claude Code send in place of a selector that
// names another lab, taken by the head wherever a selector is.
package splice.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadableIdsTest {

    private fun catalog(prefix: String, readable: ReadableIds, vararg ids: String) = ModelCatalog(
        discoveryPrefix = prefix,
        models = ids.map { ModelEntry(id = it, contextWindow = 500_000) },
        defaultContextWindow = 500_000,
        client = ClientModelPolicy(readable = readable),
    )

    private val xai = ReadableIds("claude-xai--", "grok-")
    private val grok = catalog("claude-grok--", xai, "grok-4.7", "grok-4.3", "grok-4.3[1m]")

    @Test
    fun `a readable name reaches the upstream model its selector names`() {
        assertEquals("grok-4.7", grok.unwrap("claude-xai--4.7"))
        assertEquals("grok-4.7", grok.stripSuffixes("claude-xai--4.7"))
        assertTrue(grok.contains("claude-xai--4.7"))
        assertEquals("claude-grok--grok-4.7", xai.selectorOf("claude-xai--4.7", grok))
    }

    @Test
    fun `a window hint on a readable name stays on the model`() {
        assertEquals("grok-4.3[1m]", grok.unwrap("claude-xai--4.3[1m]"))
        assertEquals("grok-4.3", grok.stripSuffixes("claude-xai--4.3[1m]"))
        assertEquals("claude-grok--grok-4.3[1m]", xai.selectorOf("claude-xai--4.3[1m]", grok))
    }

    @Test
    fun `a selector reads as it always did and is no readable name`() {
        assertEquals("grok-4.7", grok.unwrap("claude-grok--grok-4.7"))
        assertNull(xai.selectorOf("claude-grok--grok-4.7", grok))
        assertNull(xai.selectorOf("claude--claude-opus-5-5", grok))
    }

    @Test
    fun `a model that does not start with the stripped word keeps its whole id`() {
        val kimi = catalog("claude-kimi--", ReadableIds("claude-moon--", "kimi-"), "k3-256k", "kimi-for-coding")
        assertEquals("k3-256k", kimi.unwrap("claude-moon--k3-256k"))
        assertEquals("kimi-for-coding", kimi.unwrap("claude-moon--for-coding"))
    }

    @Test
    fun `the stripped word matches in any case and the model keeps its own`() {
        val glm = catalog("claude-glml53--", ReadableIds("claude-zai--", "glm-"), "GLM-5.3-Flash-Uncensored-IQ3_XXS")
        assertEquals("GLM-5.3-Flash-Uncensored-IQ3_XXS", glm.unwrap("claude-zai--5.3-Flash-Uncensored-IQ3_XXS"))
    }
}
