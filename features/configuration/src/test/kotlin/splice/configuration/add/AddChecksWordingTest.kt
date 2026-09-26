// NEW: V4-266, V4-267 (Marlin's words, from the plans take's screen) — a passing check says what the
// pass means to a new user. "✓ base url HTTP 403 from …" read as a failure beside a green tick, and
// "no model list on OPENAI_RESPONSES; 4 row(s) trusted" named a dialect nobody picked.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader

class AddChecksWordingTest {

    private val codex = ProviderConfig(
        dialect = Dialect.OPENAI_RESPONSES,
        baseUrl = "https://chatgpt.com/backend-api/codex",
        auth = AuthConfig(kind = "chatgpt-oauth"),
    )

    private fun checks(status: Int) = AddChecks(TerminalOutput { }, AddHttp { _, _, _, _ -> AddHttpReply(status, "") })

    @Test
    fun `a base url that answers is reachable, with no status code beside the tick`() {
        assertEquals(
            AddCheck("base url", true, "reachable at https://chatgpt.com/backend-api/codex"),
            checks(403).reachable(codex.baseUrl),
        )
    }

    @Test
    fun `a provider that publishes no list says its models come from splice's catalog`() {
        val checks = checks(200)
        val absent = checks.listedModels(codex, "codex", EnvReader { null })
        val why = "this provider publishes no list to check them against"
        assertEquals(
            AddCheck("models", true, "4 models from splice's catalog; $why"),
            checks.modelsListed(listOf("a", "b", "c", "d"), absent),
        )
        assertEquals("1 model from splice's catalog; $why", checks.modelsListed(listOf("a"), absent).detail)
    }
}
