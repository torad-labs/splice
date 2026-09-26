// NEW: Muse mint subs_usage mapped into QuotaSnapshot by window duration, never by name.
package splice.usage.quota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.usage.FIVE_HOUR_SLOT_MAX_SECONDS
import splice.core.usage.SEVEN_DAYS_SECONDS

class MuseQuotaTest {

    private val parser = MuseQuotaParser()
    private val now = 1_788_000_000_000L
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a 300-minute window and weekly usage land in the 5h and 7d slots`() {
        val body = obj(
            """{"api_key":"fake-key","is_subs_active":true,"require_payment":false,
               "subs_tier_id":"opaque-tier","subs_usage":{
                 "window":{"used_percent":12.5,"window_duration_mins":300,
                           "resets_at":"2026-09-15T12:00:00Z"},
                 "weekly":{"used_percent":40,"resets_at":1788864000},
                 "tier":"drop-me"}}""",
        )
        val s = parser.parse(body, now)!!
        assertEquals(12.5, s.fiveHour!!.usedPercent, 1e-9)
        assertEquals(java.time.OffsetDateTime.parse("2026-09-15T12:00:00Z").toEpochSecond(), s.fiveHour!!.resetsAt)
        assertEquals(40.0, s.sevenDay!!.usedPercent, 1e-9)
        assertEquals(1_788_864_000L, s.sevenDay!!.resetsAt)
        assertNull(s.plan)
    }

    @Test
    fun `any duration within the five-hour ceiling lands in the five-hour slot`() {
        val body = obj(
            """{"subs_usage":{"window":{"used_percent":90,"window_duration_mins":60,"resets_at":1},
               "weekly":{"used_percent":3,"resets_at":2}}}""",
        )
        val s = parser.parse(body, now)!!
        assertEquals(90.0, s.fiveHour!!.usedPercent, 1e-9)
        assertEquals(3_600L, s.fiveHour!!.windowSeconds)
        assertEquals(3.0, s.sevenDay!!.usedPercent, 1e-9)
    }

    @Test
    fun `a window longer than the five-hour ceiling is refused from that slot`() {
        val mins = FIVE_HOUR_SLOT_MAX_SECONDS / 60L + 1L
        val body = obj(
            """{"subs_usage":{"window":{"used_percent":90,"window_duration_mins":$mins,"resets_at":1},
               "weekly":{"used_percent":3,"resets_at":2}}}""",
        )
        val s = parser.parse(body, now)!!
        assertNull(s.fiveHour)
        assertEquals(3.0, s.sevenDay!!.usedPercent, 1e-9)
    }

    @Test
    fun `missing subs_usage is null and a bare usage object still parses`() {
        assertNull(parser.parse(obj("""{"api_key":"fake-key"}"""), now))
        val bare = obj("""{"window":{"used_percent":1,"window_duration_mins":300},"weekly":{"used_percent":2}}""")
        val s = parser.parse(bare, now)!!
        assertEquals(1.0, s.fiveHour!!.usedPercent, 1e-9)
        assertEquals(2.0, s.sevenDay!!.usedPercent, 1e-9)
    }

    @Test
    fun `malformed resets_at is ignored and used_percent is clamped`() {
        val body = obj(
            """{"subs_usage":{"window":{"used_percent":150,"window_duration_mins":300,"resets_at":"not-a-date"},
               "weekly":{"used_percent":-4,"resets_at":"also-bad"}}}""",
        )
        val s = parser.parse(body, now)!!
        assertEquals(100.0, s.fiveHour!!.usedPercent, 1e-9)
        assertNull(s.fiveHour!!.resetsAt)
        assertEquals(0.0, s.sevenDay!!.usedPercent, 1e-9)
        assertNull(s.sevenDay!!.resetsAt)
    }

    @Test
    fun `resets_at at 1e11 and above is milliseconds`() {
        val body = obj(
            """{"subs_usage":{"weekly":{"used_percent":1,"resets_at":100000000001}}}""",
        )
        assertEquals(100_000_000L, parser.parse(body, now)!!.sevenDay!!.resetsAt)
    }

    @Test
    fun `weekly about to roll still occupies the seven-day slot`() {
        val soon = now / 1000L + 3_600L
        val body = obj(
            """{"subs_usage":{"weekly":{"used_percent":8,"resets_at":$soon}}}""",
        )
        val s = parser.parse(body, now)!!
        assertNull(s.fiveHour)
        assertEquals(SEVEN_DAYS_SECONDS, s.sevenDay!!.windowSeconds)
    }
}
