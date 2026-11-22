// The catalog Desktop's picker gets from agy's answer to `session/new`, as agy 1.5.14 gave it on Nov 21, 2026 for a
// Google AI Ultra account (trimmed to one model of each shape).
package splice.provider.antigravity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AntigravityCatalogTest {
    private val session = Json.parseToJsonElement(
        """
        {
          "sessionId": "326cb045-f469-48b9-8726-07e0cafc354d",
          "models": {
            "availableModels": [
              {"modelId": "gemini-4-neon-high", "name": "Gemini 4 Neon (High)"},
              {"modelId": "gemini-4-neon-xhigh", "name": "Gemini 4 Neon (XHigh)"},
              {"modelId": "gemini-4-neon-medium", "name": "Gemini 4 Neon (Medium)"},
              {"modelId": "gemini-4-neon-low", "name": "Gemini 4 Neon (Low)"},
              {"modelId": "gemini-pro-orchestrator-neon", "name": "Orchestrator (Neon)"},
              {"modelId": "gemini-pro-orchestrator", "name": "Orchestrator (Carbon)"},
              {"modelId": "gemini-3.8-flash-high", "name": "Gemini 3.8 Flash (High)"},
              {"modelId": "gemini-3.8-flash-low", "name": "Gemini 3.8 Flash (Low)"},
              {"modelId": "claude-opus-5-5-high", "name": "Claude Opus 5.5 (High)"},
              {"modelId": "gpt-oss-120b-medium", "name": "GPT-OSS 120B (Medium)"}
            ],
            "currentModelId": "gemini-4-neon-high"
          },
          "configOptions": [
            {"id": "model", "category": "model", "type": "select", "currentValue": "gemini-4-neon", "options": [
              {"value": "gemini-4-neon", "name": "Gemini 4 Neon"},
              {"value": "gemini-pro-orchestrator", "name": "Orchestrator (Carbon)"},
              {"value": "gemini-3.8-flash", "name": "Gemini 3.8 Flash"},
              {"value": "claude-opus-5-5", "name": "Claude Opus 5.5"},
              {"value": "gpt-oss-120b-medium", "name": "GPT-OSS 120B (Medium)"}
            ]},
            {"id": "thought_level", "category": "thought_level", "type": "select", "currentValue": "high", "options": [
              {"value": "low", "name": "Low"},
              {"value": "medium", "name": "Medium"},
              {"value": "high", "name": "High"},
              {"value": "xhigh", "name": "Extra"}
            ]}
          ]
        }
        """,
    ).jsonObject

    private val catalog = AntigravityCatalog().of(AgyRosterReader().of(session, 1_048_576))
        ?: error("agy's answer gave no catalog")

    @Test
    fun `each model agy offers is a row under the antigravity prefix, Claude's own ids left out`() {
        assertEquals(
            listOf("gemini-4-neon", "gemini-pro-orchestrator", "gemini-3.8-flash", "gpt-oss-120b-medium"),
            catalog.models.map { it.id },
        )
        assertEquals("claude-antigravity--gemini-4-neon", catalog.wrap("gemini-4-neon"))
        assertEquals("Gemini 4 Neon", catalog.models.first().label)
    }

    @Test
    fun `a model's reasoning levels are the ones agy lists it at, starting at the session's level`() {
        val listings = catalog.models.associate { it.id to it.listed?.listing }
        assertEquals(listOf("low", "medium", "high", "xhigh"), listings["gemini-4-neon"]?.efforts)
        assertEquals("high", listings["gemini-4-neon"]?.defaultEffort)
        assertEquals(listOf("low", "high"), listings["gemini-3.8-flash"]?.efforts)
        assertEquals(emptyList<String>(), listings["gemini-pro-orchestrator"]?.efforts)
        assertNull(listings["gpt-oss-120b-medium"]?.defaultEffort)
    }

    @Test
    fun `every row has the window agy reports, and a new session starts on agy's current model`() {
        assertEquals(setOf(1_048_576L), catalog.models.map { it.contextWindow }.toSet())
        assertEquals("gemini-4-neon", catalog.pinnedModel)
    }
}
