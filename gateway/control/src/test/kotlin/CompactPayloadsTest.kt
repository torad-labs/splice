import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.control.CompactView
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.HeadUsageSource
import splice.control.ManagedHead
import splice.control.RateLimitView
import splice.control.UsageView
import splice.control.api.CompactPayloads
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.head.HeadHealth

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
            CompactPayloads(mapOf("astra-head" to managedHead(compact))).compactJson(),
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
            CompactPayloads(mapOf("head" to managedHead(compact))).compactJson(),
        ).jsonObject.getValue("stats").jsonObject.getValue("tail").jsonArray.single().jsonObject

        assertEquals("", row.getValue("instructions").jsonPrimitive.content)
        assertEquals("model:astra", row.getValue("instructions_source").jsonPrimitive.content)
    }

    private fun managedHead(compact: HeadCompactSource): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "astra-head"
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = compact,
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
    )
}
