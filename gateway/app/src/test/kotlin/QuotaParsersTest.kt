// NEW: the three provider usage bodies, as captured live on 2026-09-02 (values trimmed, ids
// replaced), reduced to the two slots. The Pro Codex shape is the one that forbids naming slots
// after the provider's own labels: its weekly window is "primary" and it has no 5-hour one.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.quota.QuotaParsers

class QuotaParsersTest {

    private val parsers = QuotaParsers()
    private val now = 1_788_000_000_000L
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `codex Pro reports one weekly window called primary, and it lands in the 7d slot`() {
        val body = obj(
            """{"plan_type":"pro","rate_limit":{"allowed":true,"limit_reached":false,
               "primary_window":{"used_percent":30,"limit_window_seconds":604800,"reset_after_seconds":497935,"reset_at":1788855387},
               "secondary_window":null}}""",
        )
        val s = parsers.codex(body, now)!!
        assertNull(s.fiveHour)
        assertEquals(30.0, s.sevenDay!!.usedPercent, 1e-9)
        assertEquals(1_788_855_387L, s.sevenDay!!.resetsAt)
        assertEquals("pro", s.plan)
    }

    @Test
    fun `codex Plus reports both windows`() {
        val body = obj(
            """{"plan_type":"plus","rate_limit":{
               "primary_window":{"used_percent":40,"limit_window_seconds":18000,"reset_after_seconds":5880},
               "secondary_window":{"used_percent":21,"limit_window_seconds":604800,"reset_at":1788500000}}}""",
        )
        val s = parsers.codex(body, now)!!
        assertEquals(40.0, s.fiveHour!!.usedPercent, 1e-9)
        assertEquals(now / 1000 + 5880, s.fiveHour!!.resetsAt)
        assertEquals(21.0, s.sevenDay!!.usedPercent, 1e-9)
    }

    @Test
    fun `kimi reports the weekly quota and a 300-minute rate window as strings`() {
        val body = obj(
            """{"user":{"membership":{"level":"LEVEL_STANDARD"}},
               "usage":{"limit":"100","remaining":"74","resetTime":"2026-09-05T04:17:14.476605Z"},
               "limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},
                          "detail":{"limit":"100","remaining":"85","resetTime":"2026-09-02T15:17:14.476605Z"}}],
               "parallel":{"limit":"30"}}""",
        )
        val s = parsers.kimi(body, now)!!
        assertEquals(15.0, s.fiveHour!!.usedPercent, 1e-9)
        assertEquals(1_788_362_234L, s.fiveHour!!.resetsAt)
        assertEquals(26.0, s.sevenDay!!.usedPercent, 1e-9)
        assertEquals("standard", s.plan)
    }

    @Test
    fun `supergrok reports one weekly period`() {
        val body = obj(
            """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-09-01T09:48:15.732924+00:00","end":"2026-09-08T09:48:15.732924+00:00"},
               "creditUsagePercent":3.0,"productUsage":[{"product":"GrokBuild","usagePercent":3.0}]}}""",
        )
        val s = parsers.grok(body, now)!!
        assertNull(s.fiveHour)
        assertEquals(3.0, s.sevenDay!!.usedPercent, 1e-9)
        assertEquals(1_788_860_895L, s.sevenDay!!.resetsAt)
        assertNull(parsers.grok(obj("""{"error":"nope"}"""), now))
    }
}
