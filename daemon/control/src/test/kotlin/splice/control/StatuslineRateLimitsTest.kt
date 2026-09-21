// NEW: V4-132 (FEATURES.md §4.5 "Claude windows", §6 "statusline rate_limits capture") — Claude
// Code's own `rate_limits` object on the statusline payload, captured WINDOW FIELDS ONLY and
// gated on `rate_limits_available` (false for API-key/Bedrock/Vertex sessions, which must never
// gate a real window behind a stale default). Direct tests of [StatuslineRateLimits] and
// [StatuslineRenderer.rateLimits], one level below the full statusline render.
package splice.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val PAYLOAD = """{
    "model":{"id":"model","display_name":"Model"},
    "rate_limits":{
        "rate_limits_available":true,
        "five_hour":{"used_percentage":12.5,"resets_at":1000},
        "seven_day":{"used_percentage":40.0,"resets_at":2000},
        "seven_day_opus":{"used_percentage":10.0,"resets_at":3000},
        "seven_day_sonnet":{"used_percentage":20.0,"resets_at":4000},
        "model_scoped":[{"model":"opus-4","used_percentage":33.0,"resets_at":5000}]
    }
}"""

class StatuslineRateLimitsTest {
    @Test
    fun `a session's rate_limits are captured with every window field, keyed by session and account`() {
        val renderer = StatuslineRenderer(label = "Codex")

        renderer.render(PAYLOAD, usage = null, warnPct = 80, warnTokens5h = 0, sessionId = "session-1")

        val capture = renderer.rateLimits.forSession("session-1")
        assertEquals(12.5, capture?.fiveHour?.usedPercent)
        assertEquals(1000L, capture?.fiveHour?.resetsAtEpochSeconds)
        assertEquals(40.0, capture?.sevenDay?.usedPercent)
        assertEquals(10.0, capture?.sevenDayOpus?.usedPercent)
        assertEquals(20.0, capture?.sevenDaySonnet?.usedPercent)
        assertEquals(1, capture?.modelScoped?.size)
        assertEquals("opus-4", capture?.modelScoped?.single()?.model)
        assertEquals(33.0, capture?.modelScoped?.single()?.usedPercent)
    }

    @Test
    fun `rate_limits_available false never captures a stale default window`() {
        val renderer = StatuslineRenderer(label = "Codex")
        val gated = PAYLOAD.replace("\"rate_limits_available\":true", "\"rate_limits_available\":false")

        renderer.render(gated, usage = null, warnPct = 80, warnTokens5h = 0, sessionId = "session-gated")

        assertNull(renderer.rateLimits.forSession("session-gated"))
    }

    @Test
    fun `a session with no session_id is never recorded, so no capture ever answers for the wrong caller`() {
        val renderer = StatuslineRenderer(label = "Codex")

        renderer.render(PAYLOAD, usage = null, warnPct = 80, warnTokens5h = 0, sessionId = null)

        assertNull(renderer.rateLimits.forSession(""))
    }

    @Test
    fun `an account's own capture is keyed by label, independent of the session read`() {
        val pool = HeadAccountPoolSource {
            HeadAccountPoolView(
                selectedLabel = "backup",
                accounts = listOf(HeadAccountView("backup", false, true, true, "plus", 1.0, 1L, 1.0, 1L)),
                lastSwitch = null,
            )
        }
        val renderer = StatuslineRenderer(label = "Codex", accountPool = pool)

        renderer.render(PAYLOAD, usage = null, warnPct = 80, warnTokens5h = 0, sessionId = "session-2")

        assertEquals(12.5, renderer.rateLimits.forAccount("backup")?.fiveHour?.usedPercent)
    }
}
