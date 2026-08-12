// 2026-08-12: /health's `ok` must mean "a turn can complete", not "heads are configured". The 91h
// wedge served {ok:true, readyHeads:4} for its whole duration because ok was a hardcoded literal.
// Pins: ok flips false with the stalled list attached; and stays a plain true (no stall array)
// when everything is live, so existing consumers see byte-identical happy-path JSON.
package splice.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import java.nio.file.Files

class ControlHealthTest {

    private fun payloads(stalled: List<String>) = ControlPayloads(
        heads = emptyMap(),
        config = ConfigService(StatePaths(baseOverride = Files.createTempDirectory("ctl-health"))),
        failedHeads = { 0 },
        configuredHeads = 0,
        turnPathStalled = { stalled },
    )

    @Test
    fun `ok stays a plain true when every turn path is live`() {
        val h = Json.parseToJsonElement(payloads(emptyList()).controlHealthJson()).jsonObject
        assertEquals(true, h["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertNull(h["turnPathStalled"], "no stall array on the happy path — consumers see the old shape")
    }

    @Test
    fun `a stalled head flips ok false and names itself`() {
        val h = Json.parseToJsonElement(payloads(listOf("claudex")).controlHealthJson()).jsonObject
        assertEquals(false, h["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertEquals(listOf("claudex"), h["turnPathStalled"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
