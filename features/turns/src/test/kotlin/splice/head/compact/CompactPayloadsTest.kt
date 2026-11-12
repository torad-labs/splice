package splice.head.compact

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.head.TurnsHead
import splice.head.TurnsHeads

class CompactPayloadsTest {

    @Test
    fun `compact poll reports the source without exposing historical instruction text`() {
        val source = "project:/work/repo model:astra file:/work/compact.txt"
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(
                total = 1,
                byOutcome = mapOf("model_text" to 1),
                tail = listOf(
                    mapOf(
                        "ts" to "42",
                        "outcome" to "model_text",
                        "instructions" to "SYNTHETIC_INSTRUCTION_ONLY",
                        "instructions_source" to source,
                    ),
                ),
            )
        }

        val payload = Json.parseToJsonElement(
            CompactPayloads(heads(compact)).compactJson(),
        ).jsonObject
        val row = payload.getValue("stats").jsonObject.getValue("tail").jsonArray.single().jsonObject

        assertEquals("astra-head", row.getValue("head").jsonPrimitive.content)
        assertFalse("instructions" in row)
        assertFalse(payload.toString().contains("SYNTHETIC_INSTRUCTION_ONLY"))
        assertEquals(source, row.getValue("instructions_source").jsonPrimitive.content)
        assertEquals(42L, row.getValue("ts").jsonPrimitive.long)
    }

    @Test
    fun `an explicit opt-out reports its source without an instruction text field`() {
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(
                1,
                mapOf("model_text" to 1),
                listOf(mapOf("instructions" to "", "instructions_source" to "model:astra")),
            )
        }

        val row = Json.parseToJsonElement(
            CompactPayloads(heads(compact)).compactJson(),
        ).jsonObject.getValue("stats").jsonObject.getValue("tail").jsonArray.single().jsonObject

        assertFalse("instructions" in row)
        assertEquals("model:astra", row.getValue("instructions_source").jsonPrimitive.content)
    }

    @Test
    fun `each head's counts carry the span they cover, and the last seven days sum across heads`() {
        fun source(span: CompactSpan?, byOutcome: Map<String, Int>) = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(byOutcome.values.sum(), byOutcome, emptyList(), span)
        }
        val bonsaiCounts = mapOf("model_text" to 1, "stream_error" to 1)
        val heads = TurnsHeads {
            listOf(
                TurnsHead(
                    "codex",
                    source(CompactSpan(10, 90, mapOf("model_text" to 4)), mapOf("model_text" to 6, "empty_model" to 3)),
                ),
                TurnsHead(
                    "bonsai",
                    source(CompactSpan(50, 80, bonsaiCounts), bonsaiCounts),
                ),
                TurnsHead("fresh", source(null, emptyMap())),
            )
        }

        val payload = Json.parseToJsonElement(CompactPayloads(heads).compactJson()).jsonObject
        val stats = payload.getValue("stats").jsonObject
        val recent = stats.getValue("by_outcome_7d").jsonObject
        assertEquals(5L, recent.getValue("model_text").jsonPrimitive.long)
        assertEquals(1L, recent.getValue("stream_error").jsonPrimitive.long)
        val codex = stats.getValue("heads").jsonObject.getValue("codex").jsonObject
        assertEquals(10L, codex.getValue("first_ts").jsonPrimitive.long)
        assertEquals(90L, codex.getValue("last_ts").jsonPrimitive.long)
        assertEquals(9L, codex.getValue("total").jsonPrimitive.long)
        assertEquals(3L, codex.getValue("by_outcome").jsonObject.getValue("empty_model").jsonPrimitive.long)
        assertEquals(4L, codex.getValue("by_outcome_7d").jsonObject.getValue("model_text").jsonPrimitive.long)
        val fresh = stats.getValue("heads").jsonObject.getValue("fresh").jsonObject
        assertEquals(setOf("total", "by_outcome", "by_outcome_7d"), fresh.keys, "no rows, no span to claim")
    }

    @Test
    fun `compact polls expose only the declared row metadata`() {
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(
                1,
                mapOf("stream_error" to 1),
                listOf(
                    mapOf(
                        "ts" to "42",
                        "outcome" to "stream_error",
                        "ms" to "7",
                        "chars" to "16",
                        "status" to "503",
                        "error" to "synthetic upstream failure",
                        "instructions_source" to "model:astra",
                        "unregistered_prompt_material" to "SYNTHETIC_INSTRUCTION_ONLY",
                    ),
                ),
            )
        }

        val payload = Json.parseToJsonElement(CompactPayloads(heads(compact)).compactJson()).jsonObject
        val row = payload.getValue("stats").jsonObject.getValue("tail").jsonArray.single().jsonObject

        assertEquals(setOf("head", "ts", "outcome", "ms", "chars", "status", "error", "instructions_source"), row.keys)
        assertFalse(payload.toString().contains("SYNTHETIC_INSTRUCTION_ONLY"))
        assertEquals(503L, row.getValue("status").jsonPrimitive.long)
        assertEquals("synthetic upstream failure", row.getValue("error").jsonPrimitive.content)
    }

    private fun heads(compact: HeadCompactSource): TurnsHeads =
        TurnsHeads { listOf(TurnsHead(key = "astra-head", compact = compact)) }
}
