package splice.app.v4379

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.app.control.api.ControlHealthReport
import splice.app.control.healthFor

class RestartBootIdentityTest {
    @Test
    fun `health holds one boot identity and a replacement daemon gets another`() {
        fun health(bootedAt: Long): ControlHealthReport =
            healthFor(emptyMap(), configuredHeads = 0, bootedAtEpochMillis = bootedAt)
        fun stamp(payloads: ControlHealthReport): Long = Json.parseToJsonElement(payloads.json())
            .jsonObject.getValue("bootedAtEpochMillis").jsonPrimitive.content.toLong()

        val first = health(1_000L)
        assertEquals(1_000L, stamp(first))
        assertEquals(1_000L, stamp(first), "repeated health reads must not mint a new boot")
        assertEquals(2_000L, stamp(health(2_000L)), "the successor must not inherit the old boot")
    }
}
