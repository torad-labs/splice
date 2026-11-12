package splice.app.head

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.API_KEY_WIRE
import splice.core.topology.ApiKeyProviderRegistry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.ModelDiscoveryConfig
import splice.core.topology.ProviderConfig

/** Goal: a line that tells a person to pay or sign in names the provider they do it with, the way they know it
 *  (DeepSeek, xAI, Anthropic), and a line about a model server on this machine says so. A provider splice cannot
 *  name is never guessed at. */
class HeadVoiceFactsTest {
    private val facts = HeadVoiceFacts()

    private fun provider(kind: String, url: String, dialect: Dialect = Dialect.OPENAI_CHAT) =
        ProviderConfig(dialect = dialect, baseUrl = url, auth = AuthConfig(kind = kind))

    @Test
    fun `a provider a person pays with a key is named as the key registry names it`() {
        for (row in ApiKeyProviderRegistry.rows()) {
            val named = facts.of(row.id, provider(API_KEY_WIRE, "https://api.${row.id}.example/v1"))
            assertEquals(row.label, named.providerName, row.id)
            assertFalse(named.local, row.id)
        }
        val deepseek = provider(API_KEY_WIRE, "https://api.deepseek.com/anthropic", Dialect.ANTHROPIC_PASSTHROUGH)
        assertEquals("DeepSeek", facts.of("deepseek", deepseek).providerName)
        assertEquals("xAI", facts.of("grok", provider(API_KEY_WIRE, "https://api.x.ai/v1")).providerName)
    }

    @Test
    fun `every sign-in splice runs names the company a person signs in with`() {
        val expected = mapOf(
            AuthKind.ChatgptOAuth to "OpenAI",
            AuthKind.GrokOAuth to "xAI",
            AuthKind.KimiOAuth to "Moonshot",
            AuthKind.MuseOAuth to "Meta",
            AuthKind.Client to "Anthropic",
        )
        // The denominator is the registry: a sign-in added there without a name here fails this test.
        assertEquals(AuthKindRegistry.knownKinds().toSet(), expected.keys)
        for ((kind, name) in expected) {
            assertEquals(name, facts.of("any", provider(kind.wire, "https://example.test")).providerName, kind.wire)
        }
    }

    @Test
    fun `a model server on this machine is local and names no provider`() {
        val bonsai = facts.of("bonsai", provider(API_KEY_WIRE, "http://127.0.0.1:8099/v1"))
        assertTrue(bonsai.local)
        assertNull(bonsai.providerName)
        val marked = facts.of("openrouter", provider(API_KEY_WIRE, "http://127.0.0.1:31990/api/v1").copy(local = true))
        assertTrue(marked.local, "the operator's own word that a server is on this machine")
    }

    @Test
    fun `a provider splice cannot name is not named, and is not taken for a local one`() {
        val corp = facts.of("my-proxy", provider(API_KEY_WIRE, "https://llm.corp.example/v1"))
        assertNull(corp.providerName)
        assertFalse(corp.local)
        val tunnel = provider(API_KEY_WIRE, "http://127.0.0.1:8100/v1")
            .copy(discovery = ModelDiscoveryConfig(family = "vast"))
        val rented = facts.of("bonsai-vast", tunnel)
        assertNull(rented.providerName, "a family the operator wrote is a colour, not a company to pay")
        assertFalse(rented.local, "the operator said this server is a rented GPU, not this machine")
    }
}
