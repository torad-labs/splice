// What the add checks conclude about a provider's model list and the context windows a head declares.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.UserHome
import splice.core.model.ModelEntry
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.Path

class AddModelWindowChecksTest {
    @Test
    fun `every declared row is capped by the provider list, not just the pinned model`(@TempDir home: Path) {
        listOf("context_length", "context_window").forEach { field -> assertOversizedRowRefused(field, home) }
    }

    private fun assertOversizedRowRefused(field: String, home: Path) = UserHome.within(home) {
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

    @Test
    fun `a declared row with no provider window is named unchecked beside the row that fits`() {
        val result = checks(200).modelWindows(
            listOf(
                ModelEntry("a", contextWindow = 128_000),
                ModelEntry("b", contextWindow = 1_000_000),
            ),
            ListedModels.Listed(listOf("a"), mapOf("a" to 128_000L)),
        )
        assertTrue(result.ok, "an unreported limit is not an observed oversized window")
        assertEquals(
            "a fits: declares 128000, provider serves 128000; b unchecked: provider lists no window size",
            result.detail,
        )
    }

    private fun checks(status: Int) = AddChecks(TerminalOutput { }, AddHttp { _, _, _, _ -> AddHttpReply(status, "") })
}
