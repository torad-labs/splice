// NEW: console review 2026-09-24 — /api/models says where each row's window came from. A head that
// declares its own context_window replaces the provider's number on every entry, rule and the
// default; the route labelled that number "model" while the console's detail said "head window 300k".
package splice.models.roster

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.model.DiscoveredModel
import splice.core.model.ModelEntry
import splice.core.model.WindowRule
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.HeadModel
import splice.core.topology.ProviderConfig

class ModelsWindowSourceTest {

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://api.x.ai/v1",
        auth = AuthConfig("api-key", env = "XAI_API_KEY"),
        models = listOf(ModelEntry("grok-4.6", label = "Grok 4.6", contextWindow = 500_000)),
        windowRules = listOf(WindowRule("grok-code", 256_000)),
    )
    private val slots = listOf(HeadModel("grok-4.6", "opus"), HeadModel("grok-code-9", "sonnet"))
    private val declared = mapOf("xai" to DeclaredHead("xai", slots))

    private fun sources(head: HeadConfig, discovered: List<DiscoveredModel> = emptyList()): Map<String, String> {
        val catalog = provider.catalogFor(head, discovered = discovered)
        val payload = ModelsRoute(listOf(RosterHead("xai", catalog))).modelsJson(declared)
        return Json.parseToJsonElement(payload).jsonObject.getValue("heads").jsonArray.single().jsonObject
            .getValue("models").jsonArray.associate {
                val row = it.jsonObject
                row.getValue("id").jsonPrimitive.content to row.getValue("context_window_source").jsonPrimitive.content
            }
    }

    @Test
    fun `a head that declares its window says so on every row`() {
        val head = HeadConfig("xai", 4104, "claude-grok--", "grok-4.6", contextWindow = 300_000)
        assertEquals(mapOf("grok-4.6" to "head", "grok-code-9" to "head"), sources(head))
    }

    @Test
    fun `a head without its own window keeps the provider's provenance`() {
        val head = HeadConfig("xai", 4104, "claude-grok--", "grok-4.6")
        assertEquals(mapOf("grok-4.6" to "model", "grok-code-9" to "rule"), sources(head))
    }

    @Test
    fun `a discovered model's published ceiling under the head's window is the model's number`() {
        val head = HeadConfig("xai", 4104, "claude-grok--", "grok-4.6", contextWindow = 300_000)
        val discovered = listOf(DiscoveredModel("grok-4.7", contextWindow = 128_000))
        assertEquals("model", sources(head, discovered)["grok-4.7"])
    }

    @Test
    fun `a live window edit carries where the new numbers came from`() {
        val boot = provider.catalogFor(HeadConfig("xai", 4104, "claude-grok--", "grok-4.6"))
        val edited = provider.catalogFor(HeadConfig("xai", 4104, "claude-grok--", "grok-4.6", contextWindow = 300_000))
        assertEquals(300_000L, boot.withWindowsOf(edited).headWindow)
        assertEquals(null, edited.withWindowsOf(boot).headWindow)
    }
}
