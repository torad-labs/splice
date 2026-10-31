// NEW: V4-441 — the Codex backend's `tool_mode` per model, read from its own catalog into the discovered
// models, kept across starts, and absent from a list kept before it existed. The body is the backend's
// GET /models read on 2026-09-29 cut to the fields the parser reads: gpt-6.1-sol shipped that day marked
// code_mode_only, gpt-5.5 has `tool_mode: null`, and gpt-reserve and codex-auto-review are code_mode_only
// rows the backend hides from its picker (visibility "hide"), which discovery drops.
package splice.models.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.models.list.ModelCredentialSource
import splice.models.list.ProbedProvider
import splice.models.list.UpstreamRosterParser
import java.nio.file.Files
import java.nio.file.Path

private const val URL = "https://chatgpt.com/backend-api/codex/models?client_version=999.0.0"

private const val CATALOG = """{"models":[
  {"slug":"gpt-6.1-sol","display_name":"GPT-6.1-Sol","visibility":"list","tool_mode":"code_mode_only","context_window":272000,"max_context_window":872000},
  {"slug":"gpt-6-astra","display_name":"GPT-6-Astra","visibility":"list","tool_mode":"code_mode_only","context_window":272000,"max_context_window":872000},
  {"slug":"gpt-reserve","display_name":"GPT-Reserve","visibility":"hide","tool_mode":"code_mode_only","context_window":272000,"max_context_window":872000},
  {"slug":"gpt-5.5","display_name":"GPT-5.5","visibility":"list","tool_mode":null,"context_window":272000,"max_context_window":272000},
  {"slug":"codex-auto-review","display_name":"Codex Auto Review","visibility":"hide","tool_mode":"code_mode_only","context_window":272000,"max_context_window":872000}
]}"""

class CodexToolModeTest {

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_RESPONSES,
        baseUrl = "https://chatgpt.com/backend-api/codex",
        auth = AuthConfig("chatgpt-oauth"),
    )
    private val discovery = ModelDiscovery(ModelCredentialSource { _, _, _ -> "unused" })

    private fun found(): Discovery.Found {
        val roster = UpstreamRosterParser().parse(CATALOG, URL)
        return discovery.answer(ProbedProvider("codex", provider, URL, roster)) as Discovery.Found
    }

    @Test
    fun `the backend's tool_mode reaches the discovered models, and gpt-5_5 has none`() {
        val byId = found().models.associateBy { it.id }
        assertTrue(byId.getValue("gpt-6.1-sol").codeModeOnly)
        assertTrue(byId.getValue("gpt-6-astra").codeModeOnly)
        assertFalse(byId.getValue("gpt-5.5").codeModeOnly)
        assertEquals(null, byId.getValue("gpt-5.5").toolMode, "a JSON null is no mode")
    }

    @Test
    fun `the hidden code_mode_only rows are dropped by discovery, so only code_mode_models can name them`() {
        val found = found()
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-5.5"), found.models.map { it.id })
        assertEquals(2, found.ruledOut)
    }

    @Test
    fun `the kept list keeps the flag, and a list kept before it loads with none`(@TempDir tmp: Path) {
        val paths = StatePaths(baseOverride = tmp)
        val cache = RosterCache(paths)
        cache.write("codex", found())
        val kept = (cache.read("codex", provider) as KeptRoster.Kept).models
        assertEquals(found().models, kept)
        assertTrue(kept.first { it.id == "gpt-6.1-sol" }.codeModeOnly)

        Files.writeString(
            paths.modelRosterFile("codex"),
            """{"url":"$URL","models":[{"id":"gpt-6.1-sol","label":"GPT-6.1-Sol","context_window":272000}]}""",
        )
        val old = (cache.read("codex", provider) as KeptRoster.Kept).models
        assertEquals(listOf(DiscoveredModel("gpt-6.1-sol", "GPT-6.1-Sol", 272_000)), old)
        assertFalse(old.single().codeModeOnly, "written before the flag existed")
    }
}
