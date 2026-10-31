// NEW: console review 2026-09-24 — the windows /api/models reports for each row: the number in force, and the
// ceiling the provider publishes. Its context_window_source cases went on Oct 12, 2026 with the field (marlin's call).
package splice.models.roster

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    @Test
    fun `forwarded model rows report the capped native window`() {
        val native = provider.copy(
            auth = AuthConfig("client"),
            models = listOf(
                ModelEntry("native-small", contextWindow = 200_000),
                ModelEntry("native-wide", contextWindow = 1_000_000),
            ),
        )
        val head = HeadConfig("native", 4104, "native--", "native-wide", contextWindow = 1_000_000)
        val catalog = native.catalogFor(head)
        val payload = ModelsRoute(listOf(RosterHead("native", catalog)))
            .modelsJson(mapOf("native" to DeclaredHead("native", null)))
        val rows = Json.parseToJsonElement(payload).jsonObject.getValue("heads").jsonArray.single().jsonObject
            .getValue("models").jsonArray.associate { row ->
                row.jsonObject.getValue("id").jsonPrimitive.content to row.jsonObject
            }
        assertEquals(200_000L, rows.getValue("native-small").getValue("context_window").jsonPrimitive.long)
        assertEquals(1_000_000L, rows.getValue("native-wide").getValue("context_window").jsonPrimitive.long)
    }

    @Test
    fun `a model the provider publishes a ceiling for reports it, whatever window the head runs it at`() {
        val head = HeadConfig("xai", 4104, "claude-grok--", "grok-4.6", contextWindow = 300_000)
        val discovered = listOf(DiscoveredModel("grok-4.7", contextWindow = 128_000, maxContextWindow = 1_048_576))
        val payload = ModelsRoute(listOf(RosterHead("xai", provider.catalogFor(head, discovered = discovered))))
            .modelsJson(declared)
        val rows = Json.parseToJsonElement(payload).jsonObject.getValue("heads").jsonArray.single().jsonObject
            .getValue("models").jsonArray.associate { row ->
                row.jsonObject.getValue("id").jsonPrimitive.content to row.jsonObject
            }
        assertEquals(1_048_576L, rows.getValue("grok-4.7").getValue("max_context_window").jsonPrimitive.long)
        assertEquals(null, rows.getValue("grok-4.6")["max_context_window"], "a declared row has no published ceiling")
    }

    @Test
    fun `a live window edit carries where the new numbers came from`() {
        val boot = provider.catalogFor(HeadConfig("xai", 4104, "claude-grok--", "grok-4.6"))
        val edited = provider.catalogFor(HeadConfig("xai", 4104, "claude-grok--", "grok-4.6", contextWindow = 300_000))
        assertEquals(300_000L, boot.withWindowsOf(edited).windows.headWindow)
        assertEquals(null, edited.withWindowsOf(boot).windows.headWindow)
    }
}
