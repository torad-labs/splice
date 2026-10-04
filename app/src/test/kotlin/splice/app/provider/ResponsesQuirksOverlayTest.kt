// NEW (2026-10-01): the TOML -> ResponsesQuirks overlay for summary_delivery, pinned where splice.toml
// becomes the codex head's wire. On gpt-6.1-sol the codex default, sequential_cutoff, returned 161 of
// 165 reasoning summaries empty, so the head showed nothing for a whole reasoning phase; `off` is the
// operator's way back to the backend's default delivery, and it must reach the quirks the turn uses.
package splice.app.provider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.provider.codex.CodexQuirks
import splice.topology.TopologyLoader
import java.nio.file.Path

class ResponsesQuirksOverlayTest {

    private fun toml(quirks: String) = """
        [providers.codex]
        dialect = "openai-responses"
        base_url = "https://chatgpt.com/backend-api/codex"
        auth = { kind = "chatgpt-oauth" }
        $quirks

        [[providers.codex.models]]
        id = "gpt-6.1-sol"
        context_window = 400000

        [heads.claudex]
        provider = "codex"
        port = 3399
        discovery_prefix = "claude-codex"
        pinned_model = "gpt-6.1-sol"
    """.trimIndent()

    private fun delivery(quirks: String, tmp: Path): String? = QuirksOverlay().responsesQuirks(
        TopologyLoader.parse(toml(quirks)).providers.getValue("codex"),
        CodexQuirks().defaultQuirks(),
        ConfigService(StatePaths(baseOverride = tmp), envReader = { null }).getConfig(),
    ).summaryDelivery

    @Test
    fun `absent keeps the codex default, off omits it, and the named mode is sent`(@TempDir tmp: Path) {
        assertEquals("sequential_cutoff", delivery("", tmp))
        assertNull(delivery("""quirks = { summary_delivery = "off" }""", tmp))
        assertEquals("sequential_cutoff", delivery("""quirks = { summary_delivery = "sequential_cutoff" }""", tmp))
    }

    // A mode the backend does not define never loads: the field is an enum, so a typo is a load
    // failure rather than a value sent upstream on every turn.
    @Test
    fun `an unknown summary_delivery fails at load`(@TempDir tmp: Path) {
        assertThrows(Exception::class.java) { delivery("""quirks = { summary_delivery = "sequential" }""", tmp) }
    }
}
