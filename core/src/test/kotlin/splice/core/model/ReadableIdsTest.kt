// A head's readable model names (ReadableNames): what Claude Desktop and its Claude Code send in place of a selector
// that names another lab, taken by the head wherever a selector is. splice.toml may give them (ReadableIds); with
// nothing set, ClientModelNames makes them.
package splice.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertEquals("claude-grok--grok-4.7", grok.names.selectorOf("claude-xai--4.7"))
    }

    @Test
    fun `a window hint on a readable name stays on the model`() {
        assertEquals("grok-4.3[1m]", grok.unwrap("claude-xai--4.3[1m]"))
        assertEquals("grok-4.3", grok.stripSuffixes("claude-xai--4.3[1m]"))
        assertEquals("claude-grok--grok-4.3[1m]", grok.names.selectorOf("claude-xai--4.3[1m]"))
    }

    @Test
    fun `a selector reads as it always did and is no readable name`() {
        assertEquals("grok-4.7", grok.unwrap("claude-grok--grok-4.7"))
        assertNull(grok.names.selectorOf("claude-grok--grok-4.7"))
        assertNull(grok.names.selectorOf("claude--claude-opus-5-5"))
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

    private fun plain(prefix: String, vararg ids: String) = ModelCatalog(
        discoveryPrefix = prefix,
        models = ids.map { ModelEntry(id = it, contextWindow = 500_000) },
        defaultContextWindow = 500_000,
    )

    @Test
    fun `with nothing set a head whose selectors name another lab has readable names both ways`() {
        val codex = plain("claude-codex--", "gpt-6.2-sol", "gpt-6-luna")
        assertEquals("claude-oai--6.2-sol", codex.names.nameOf("gpt-6.2-sol"))
        assertEquals("gpt-6.2-sol", codex.unwrap("claude-oai--6.2-sol"))
        assertEquals("claude-codex--gpt-6-luna", codex.names.selectorOf("claude-oai--6-luna"))
        assertEquals("gpt-6.2-sol", codex.unwrap("claude-codex--gpt-6.2-sol"))
    }

    @Test
    fun `a provider keyed by a lab's other name leaves the lab's name out of its models, as its head does`() {
        val xaiProvider = plain("claude-xai--", "grok-4.7", "grok-build-latest")
        assertEquals("claude-xai--4.7", xaiProvider.names.nameOf("grok-4.7"))
        assertEquals("claude-xai--build-latest", xaiProvider.names.nameOf("grok-build-latest"))
        assertEquals("grok-4.7", xaiProvider.unwrap("claude-xai--4.7"))
        assertEquals("claude-xai--4.7", grok.names.nameOf("grok-4.7"), "the same id the head's own table gives")
    }

    @Test
    fun `the whole name a client was given before the short one still reaches its model`() {
        val xaiProvider = plain("claude-xai--", "grok-4.7", "grok-4.3[1m]")
        assertEquals("grok-4.7", xaiProvider.unwrap("claude-xai--xai-4.7"))
        assertEquals("grok-4.3[1m]", xaiProvider.unwrap("claude-xai--xai-4.3[1m]"))
        assertEquals("claude-xai--grok-4.7", xaiProvider.names.selectorOf("claude-xai--xai-4.7"))
        assertEquals("claude-xai--4.7", xaiProvider.names.nameOf("grok-4.7"), "and is never the name it is listed by")
    }

    @Test
    fun `a selector Claude's clients keep is its own name`() {
        val claude = plain("claude--", "claude-opus-5-5")
        assertEquals("claude--claude-opus-5-5", claude.names.nameOf("claude-opus-5-5"))
        assertNull(claude.names.selectorOf("claude--claude-opus-5-5"))
        val muse = plain("claude-muse--", "muse-wonder-full-1.0[1m]")
        assertEquals("claude-muse--muse-wonder-full-1.0[1m]", muse.names.nameOf("muse-wonder-full-1.0[1m]"))
    }

    @Test
    fun `two models one short name would fit keep apart`() {
        val kimi = plain("claude-kimi--", "k3", "kimi-k3")
        assertEquals("claude-moon--k3", kimi.names.nameOf("k3"))
        assertEquals("claude-moon--moon-k3", kimi.names.nameOf("kimi-k3"))
        assertEquals("kimi-k3", kimi.unwrap("claude-moon--moon-k3"))
    }

    @Test
    fun `every name it makes is one Claude's clients keep`() {
        val ids = listOf("gpt-6.2-sol", "grok-4.7", "kimi-for-coding", "deepseek-v4-pro", "GLM-5.3-Flash", "qwen4-max")
        val heads = listOf("claude-codex--", "claude-grok--", "claude-kimi--", "claude-deepseek--", "claude-glml53--")
        heads.forEach { prefix ->
            ids.forEach { id ->
                assertTrue(ClientModelNames.kept(ClientModelNames.readable(prefix + id, prefix)), "$prefix$id")
            }
        }
        assertTrue(ClientModelNames.kept("claude-openrouter--anthropic/claude-haiku-4.5"))
        assertFalse(ClientModelNames.kept("claude-grok--grok-4.7"))
    }
}
