// The `emit_reasoning` provider quirk gates whether the openai-chat dialect sends the
// `reasoning`/`reasoning_effort` request fields. Strict OpenAI-compatible vendors (e.g. Fireworks)
// reject unknown request fields, so it must be settable from TOML — and must default to true so
// existing OpenRouter/xAI configs keep emitting it unchanged.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.TopologyLoader
import splice.core.topology.Dialect

class EmitReasoningQuirkTest {

    @Test
    fun `emit_reasoning defaults to true when the quirk is omitted`() {
        val toml = """
            [daemon]
            control_port = 3096

            [providers.openrouter]
            dialect = "openai-chat"
            base_url = "https://openrouter.ai/api/v1"
            auth = { kind = "api-key", env = "OPENROUTER_API_KEY" }
            [[providers.openrouter.models]]
            id = "some/model"
            context_window = 200000

            [heads.openrouter]
            provider = "openrouter"
            port = 3101
            discovery_prefix = "claude-openrouter--"
            pinned_model = "some/model"
            [heads.openrouter.claude]
            command = "claudeor"
        """.trimIndent()

        val provider = TopologyLoader.parse(toml).providers.getValue("openrouter")
        assertEquals(Dialect.OPENAI_CHAT, provider.dialect)
        assertTrue(provider.quirks.emitReasoning, "emit_reasoning should default to true")
    }

    @Test
    fun `emit_reasoning = false is parsed from the provider quirks table`() {
        val toml = """
            [daemon]
            control_port = 3096

            [providers.fireworks]
            dialect = "openai-chat"
            base_url = "https://api.fireworks.ai/inference/v1"
            auth = { kind = "api-key", env = "FIREWORKS_API_KEY" }
            quirks = { emit_reasoning = false }
            [[providers.fireworks.models]]
            id = "accounts/fireworks/models/deepseek-v4-pro"
            context_window = 1048576

            [heads.fireworks]
            provider = "fireworks"
            port = 3103
            discovery_prefix = "claude-fireworks--"
            pinned_model = "accounts/fireworks/models/deepseek-v4-pro"
            [heads.fireworks.claude]
            command = "claudefw"
        """.trimIndent()

        val provider = TopologyLoader.parse(toml).providers.getValue("fireworks")
        assertFalse(
            provider.quirks.emitReasoning,
            "emit_reasoning = false must disable the reasoning request field",
        )
    }
}
