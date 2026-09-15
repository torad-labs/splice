// NEW: Muse mint subs_usage mapped into QuotaSnapshot by window duration, never by name.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.quota.MuseQuotaParser

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
    fun `a non-300-minute window is refused from the five-hour slot`() {
        val body = obj(
            """{"subs_usage":{"window":{"used_percent":90,"window_duration_mins":60,"resets_at":1},
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
}
