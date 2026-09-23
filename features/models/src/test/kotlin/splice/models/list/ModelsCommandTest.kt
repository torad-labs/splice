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
                "  \u001B[2mdeclared:\u001B[0m known",
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

    private fun clientProvider() = ProviderConfig(
        dialect = Dialect.ANTHROPIC_PASSTHROUGH,
        baseUrl = "https://example.invalid",
        auth = AuthConfig(kind = "client"),
    )
}
