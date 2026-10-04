// NEW: V4-266, V4-267 (Marlin's words, from the plans take's screen) — a passing check says what the
// pass means to a new user. "✓ base url HTTP 403 from …" read as a failure beside a green tick, and
// "no model list on OPENAI_RESPONSES; 4 row(s) trusted" named a dialect nobody picked.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import java.nio.file.Path

class AddChecksWordingTest {

    private val codex = ProviderConfig(
        dialect = Dialect.OPENAI_RESPONSES,
        baseUrl = "https://chatgpt.com/backend-api/codex",
        auth = AuthConfig(kind = "chatgpt-oauth"),
    )

    @ParameterizedTest
    @ValueSource(strings = ["context_length", "context_window"])
    fun `every declared row is capped by the provider list, not just the pinned model`(
        field: String,
        @TempDir home: Path,
    ) = UserHome.within(home) {
        val env = EnvReader { name -> if (name == "FW_API_KEY") "synthetic-key" else null }
        val output = TerminalOutput { }
        val http = AddHttp { _, url, _, _ ->
            val body = if (url.endsWith("/models")) {
                """{"data":[{"id":"m","$field":1000},{"id":"extra","$field":1000}]}"""
            } else {
                "{}"
            }
            AddHttpReply(200, body)
        }
        val checks = AddChecks(output, http)
        val prepare = AddPrepare(output, checks, AddPrompter { _, default -> default }, HeadPortBindable { true })
        val args = AddArgs(
            profile = "api-key",
            name = "fw",
            baseUrl = "http://localhost:1/v1",
            models = listOf("m:1000", "extra:2000"),
            yes = true,
        )
        val candidate = requireNotNull(prepare.candidate(args, env))
        val results = checks.all(candidate, live = false, env)
        assertFalse(results.all { it.ok }, "a listed but oversized extra row must refuse the add: $results")
        assertTrue(results.any { !it.ok && it.detail.contains("extra") && it.detail.contains("1000") }, "$results")
    }

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
