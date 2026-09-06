import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.TopologyLoader

class CodeModeConfigTest {

    private fun topology(authKind: String, dialect: String, codeMode: Boolean?): String {
        val quirks = codeMode?.let { "[providers.team-chatgpt.quirks]\ncode_mode = $it" }.orEmpty()
        return """
            [providers.team-chatgpt]
            dialect = "$dialect"
            base_url = "https://example.invalid"
            auth = { kind = "$authKind" }
            $quirks

            [heads.engineering]
            provider = "team-chatgpt"
            port = 4100
            discovery_prefix = "team-chatgpt--"
            pinned_model = "gpt-6-astra"
        """.trimIndent()
    }

    @Test
    fun `TOML accepts every code mode state for custom ChatGPT provider and head names`() {
        for (enabled in listOf(null, false, true)) {
            val parsed = TopologyLoader.parse(topology("chatgpt-oauth", "openai-responses", enabled))
            assertEquals(enabled, parsed.providers.getValue("team-chatgpt").quirks.codeMode)
            assertEquals("team-chatgpt", parsed.heads.getValue("engineering").provider)
        }
    }

    @Test
    fun `TOML rejects unsupported code mode auth and dialect combinations`() {
        val unsupported = listOf(
            "ChatGPT wrong dialect" to ("chatgpt-oauth" to "openai-chat"),
            "Grok" to ("grok-oauth" to "openai-responses"),
            "Kimi" to ("kimi-oauth" to "anthropic-passthrough"),
            "Claude client" to ("client" to "anthropic-passthrough"),
            "api-key" to ("api-key" to "openai-responses"),
            "local" to ("local" to "openai-responses"),
            "none" to ("none" to "openai-responses"),
            "custom auth" to ("workspace-oauth" to "openai-responses"),
        )

        unsupported.forEach { (label, authAndDialect) ->
            val (kind, dialect) = authAndDialect
            for (enabled in listOf(null, false)) {
                assertDoesNotThrow(
                    { TopologyLoader.parse(topology(kind, dialect, codeMode = enabled)) },
                    "$label: code_mode=$enabled",
                )
            }
            val error = assertThrows(IllegalArgumentException::class.java) {
                TopologyLoader.parse(topology(kind, dialect, codeMode = true))
            }
            assertTrue(error.message.orEmpty().contains("code_mode"), "$label: ${error.message}")
            assertTrue(error.message.orEmpty().contains("chatgpt-oauth"), "$label: ${error.message}")
            assertTrue(error.message.orEmpty().contains("openai-responses"), "$label: ${error.message}")
        }
    }
}
