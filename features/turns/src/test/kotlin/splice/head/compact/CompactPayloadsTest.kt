package splice.head.compact

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.head.TurnsHead
import splice.head.TurnsHeads

class CompactPayloadsTest {

    @Test
    fun `compact payload exposes effective instruction text and source unchanged`() {
        val source = "project:/work/repo model:astra file:/work/compact.txt"
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(
                total = 1,
                byOutcome = mapOf("model_text" to 1),
                tail = listOf(
                    mapOf(
                        "ts" to "42",
                        "outcome" to "model_text",
                        "instructions" to "retain decisions",
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
        assertEquals("retain decisions", row.getValue("instructions").jsonPrimitive.content)
        assertEquals(source, row.getValue("instructions_source").jsonPrimitive.content)
        assertEquals(42L, row.getValue("ts").jsonPrimitive.long)
    }

    @Test
    fun `explicit opt-out remains inspectable as empty text with its configured source`() {
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

        assertEquals("", row.getValue("instructions").jsonPrimitive.content)
        assertEquals("model:astra", row.getValue("instructions_source").jsonPrimitive.content)
    }

    private fun heads(compact: HeadCompactSource): TurnsHeads =
        TurnsHeads { listOf(TurnsHead(key = "astra-head", compact = compact)) }
}
