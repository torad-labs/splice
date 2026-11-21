// NEW: gateway-no-heads. Which provider shares books with which head, and that a provider's kept state survives the
// daemon-start sweep of removed heads only because the daemon names its key.
package splice.app.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

class BooksKeysTest {
    private fun provider() = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "http://127.0.0.1:1",
        auth = AuthConfig(kind = "api-key"),
    )

    private fun head(provider: String) =
        HeadConfig(provider = provider, port = 1, discoveryPrefix = "claude-$provider--")

    @Test
    fun `a provider keeps its books under its own name, which a head of that name serving it shares`() {
        val topology = Topology(
            providers = mapOf("grok" to provider(), "codex" to provider()),
            heads = mapOf("grok" to head("grok")),
        )

        val keys = BooksKeys(topology)

        assertEquals("grok", keys.forProvider("grok"), "the head named grok serves grok: one identity, one key")
        assertEquals("codex", keys.forProvider("codex"), "a provider with no head keeps its name")
    }

    @Test
    fun `a head named like a provider but serving another owns the name, and the provider moves off it`() {
        val topology = Topology(
            providers = mapOf("codex" to provider(), "openrouter" to provider()),
            heads = mapOf("codex" to head("openrouter")),
        )

        val keys = BooksKeys(topology)

        assertEquals("providers.codex", keys.forProvider("codex"), "the head's files are the head's")
        assertEquals("openrouter", keys.forProvider("openrouter"))
        assertEquals(setOf("providers.codex", "openrouter"), keys.all())
    }

    @Test
    fun `another head on a provider keeps its own books and the provider keeps its own`() {
        val topology = Topology(
            providers = mapOf("codex" to provider()),
            heads = mapOf("claudex" to head("codex")),
        )

        assertEquals("codex", BooksKeys(topology).forProvider("codex"))
    }

    @Test
    fun `with no heads at all every provider has a key`() {
        val topology = Topology(providers = mapOf("inkling" to provider(), "acme" to provider()))

        assertEquals(setOf("inkling", "acme"), BooksKeys(topology).all())
    }

    @Test
    fun `a headless provider's kept state is deleted at start as a removed head's unless the daemon names its key`(
        @TempDir tmp: Path,
    ) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val topology = Topology(providers = mapOf("inkling" to provider()))
        val reasoning = paths.headsDir.resolve("inkling/reasoning/splice-0123.jsonl")
        Files.createDirectories(reasoning.parent)
        Files.writeString(reasoning, "{}\n")
        val kept = HeadKeptFiles(paths, LogSink { })

        kept.ofRemovedHeads(topology.heads.keys + BooksKeys(topology).all())
        assertTrue(Files.exists(reasoning), "the daemon named inkling's key, so its state stays")

        kept.ofRemovedHeads(topology.heads.keys)
        assertFalse(Files.exists(reasoning), "told only of heads, the sweep takes inkling's state for a removed head's")
    }
}
