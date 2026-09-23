package splice.models.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader

class ModelsCommandTest {
    @Test
    fun `an unknown provider names its configuration without reading credentials`() {
        val lines = mutableListOf<String>()
        val command = ModelsCommand(
            ModelConfigurationSource { ModelConfiguration("/fixture/splice.toml", mapOf("known" to clientProvider())) },
            ModelCredentialSource { _, _, _ -> error("an unknown provider must not read a credential") },
            ModelReportOutput { lines.add(it) },
        )

        assertFalse(command.models(listOf("missing"), EnvReader { null }))
        assertEquals(
            listOf(
                "splice: no provider 'missing' in /fixture/splice.toml",
                "  declared: known",
            ),
            lines,
        )
    }

    @Test
    fun `a failed provider does not hide a later provider from the report`() {
        val lines = mutableListOf<String>()
        val invalid = ProviderConfig(
            dialect = Dialect.OPENAI_CHAT,
            baseUrl = "https://example.invalid",
            auth = AuthConfig(kind = "api-key"),
            modelsUrl = "://invalid",
        )
        val command = ModelsCommand(
            ModelConfigurationSource {
                ModelConfiguration(
                    "/fixture/splice.toml",
                    linkedMapOf("broken" to invalid, "later" to clientProvider()),
                )
            },
            ModelCredentialSource { _, _, _ -> null },
            ModelReportOutput { lines.add(it) },
        )

        assertFalse(command.models(emptyList(), EnvReader { null }))
        assertTrue(lines.any { it.contains("could not be asked") })
        assertTrue(lines.any { it.contains("later") }, "the report must not short-circuit after the first fault")
        assertTrue(lines.any { it.contains("your own Claude login") })
    }

    @Test
    fun `NO_COLOR in the caller's env reaches the report, which is then its plain text`() {
        // The report used to paint with raw SGR constants whatever the env said, so NO_COLOR, a dumb
        // TERM and a pipe all received escape bytes. Both runs render the same no-network provider.
        val painted = render(mapOf("TERM" to "xterm-256color"))
        val plain = render(mapOf("TERM" to "xterm-256color", "NO_COLOR" to ""))
        assertTrue(painted.any { ESC in it }, "the control run carried no colour, so the arm below proves nothing")
        assertFalse(plain.any { ESC in it }, "an SGR sequence reached a NO_COLOR report: $plain")
        assertEquals(painted.map { it.replace(SGR, "") }, plain, "colour is never the only carrier")
    }

    private fun render(env: Map<String, String>): List<String> {
        val lines = mutableListOf<String>()
        ModelsCommand(
            ModelConfigurationSource { ModelConfiguration("/fixture/splice.toml", mapOf("mine" to clientProvider())) },
            ModelCredentialSource { _, _, _ -> null },
            ModelReportOutput { lines.add(it) },
        ).models(emptyList(), EnvReader { env[it] })
        return lines
    }

    private fun clientProvider() = ProviderConfig(
        dialect = Dialect.ANTHROPIC_PASSTHROUGH,
        baseUrl = "https://example.invalid",
        auth = AuthConfig(kind = "client"),
    )
}

private const val ESC = "\u001B"
private val SGR = Regex("\u001B\\[[0-9;]*m")
