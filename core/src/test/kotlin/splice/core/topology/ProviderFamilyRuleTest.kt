package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProviderFamilyRuleTest {
    private val rule = ProviderFamilyRule()

    private fun provider(kind: String, url: String, dialect: Dialect = Dialect.OPENAI_CHAT) =
        ProviderConfig(dialect = dialect, baseUrl = url, auth = AuthConfig(kind = kind))

    @Test
    fun `every registered auth kind names a family, so a new kind cannot arrive uncoloured`() {
        // The denominator is the registry, not a list kept here: a kind added there without a branch
        // in the rule fails to compile, and a branch answering null for it fails here.
        for (kind in AuthKindRegistry.knownKinds()) {
            assertNotNull(rule.of("any", provider(kind.wire, "https://example.test")), kind.wire)
        }
    }

    @Test
    fun `the family follows the provider, never the wire it speaks`() {
        // The operator's claude-deepseek: the Anthropic dialect, an api key, DeepSeek's host.
        val deepseek = provider(API_KEY_WIRE, "https://api.deepseek.com/anthropic", Dialect.ANTHROPIC_PASSTHROUGH)
        assertEquals("deepseek", rule.of("deepseek", deepseek))
        assertEquals("anthropic", rule.of("anthropic", provider("client", "https://api.anthropic.com")))
        assertEquals("openai", rule.of("codex", provider("chatgpt-oauth", "https://chatgpt.com/backend-api/codex")))
        assertEquals("moonshot", rule.of("kimi", provider("kimi-oauth", "https://api.kimi.com/coding")))
        assertEquals("xai", rule.of("grok", provider(API_KEY_WIRE, "https://api.x.ai/v1")), "the registry's alias")
    }

    @Test
    fun `runtimes on the operator's own machine are one family, whatever each is called`() {
        for (key in listOf("bonsai", "bonsai-vast", "bonsai-second")) {
            assertEquals("local", rule.of(key, provider(API_KEY_WIRE, "http://127.0.0.1:8099/v1")), key)
        }
    }

    @Test
    fun `a provider nothing names has no family, rather than a guessed one`() {
        assertNull(rule.of("my-proxy", provider(API_KEY_WIRE, "https://llm.corp.example/v1")))
        assertNull(rule.of("my-proxy", provider("custom-sso", "https://llm.corp.example/v1")))
    }
}
