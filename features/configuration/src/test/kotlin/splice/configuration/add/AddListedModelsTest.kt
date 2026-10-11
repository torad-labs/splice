// What `splice add` concludes about a provider's model list per dialect: read where splice can, said plainly where it
// cannot, and a list that fails on openai-responses never stops the add.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader

class AddListedModelsTest {
    private val env = EnvReader { name -> if (name == "RESP_KEY") "synthetic-key" else null }

    private fun provider(dialect: Dialect, base: String = "https://api.example.test/v1") = ProviderConfig(
        dialect = dialect,
        baseUrl = base,
        auth = AuthConfig(kind = "api-key", env = "RESP_KEY"),
    )

    @Test
    fun `an openai-responses list is read from the url splice models asks, in either envelope`() {
        val asked = mutableListOf<String>()
        val http = AddHttp { _, url, _, _ ->
            asked += url
            AddHttpReply(200, """{"models":[{"slug":"gpt-a","context_window":400000},{"slug":"gpt-b"}]}""")
        }
        val checks = AddChecks(TerminalOutput { }, http)

        val listed = checks.listedModels(provider(Dialect.OPENAI_RESPONSES), "resp", env)

        assertEquals(listOf("https://api.example.test/v1/models"), asked)
        assertEquals(listOf("gpt-a", "gpt-b"), (listed as ListedModels.Listed).ids)
        assertEquals(mapOf("gpt-a" to 400_000L), listed.windows)
    }

    @Test
    fun `an openai-responses list that cannot be read warns and lets the add go on with the entered models`() {
        val checks = AddChecks(TerminalOutput { }, AddHttp { _, _, _, _ -> AddHttpReply(500, "") })

        val listed = checks.listedModels(provider(Dialect.OPENAI_RESPONSES), "resp", env)
        val verdict = checks.modelsListed(listOf("gpt-a"), listed)

        assertTrue(verdict.ok, "an unreadable responses list must not refuse the add: $verdict")
        assertTrue(verdict.detail.startsWith("WARNING: HTTP 500"), verdict.detail)
        assertTrue(verdict.detail.contains("used as given"), verdict.detail)
    }

    @Test
    fun `an openai-chat list that cannot be read still refuses the add`() {
        val checks = AddChecks(TerminalOutput { }, AddHttp { _, _, _, _ -> AddHttpReply(500, "") })

        val verdict = checks.modelsListed(
            listOf("m"),
            checks.listedModels(provider(Dialect.OPENAI_CHAT), "chat", env),
        )

        assertEquals(false, verdict.ok)
    }

    @Test
    fun `a passthrough provider is said to have no credential splice can read its list with`() {
        val checks = AddChecks(TerminalOutput { }, AddHttp { _, _, _, _ -> error("nothing may be asked") })

        val listed = checks.listedModels(provider(Dialect.ANTHROPIC_PASSTHROUGH), "pass", env)
        val verdict = checks.modelsListed(listOf("claude-x"), listed)

        assertEquals(ListedModels.NoCredential, listed)
        assertTrue(verdict.ok)
        assertTrue(verdict.detail.contains("forwards your own login"), verdict.detail)
    }
}
