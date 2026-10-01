package splice.usage.quota.v4396

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeads
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsagePayloads
import splice.usage.quota.UsageView
import splice.usage.statusline.StatuslineRenderer
import java.nio.file.Path

/** V4-396: /api/usage shows a plan window only while it is current — read under 15 minutes ago and
 *  not yet reset — the rule the status line already applies (QuotaFreshness). Marlin's header read
 *  "claude-splice 7d 99%" from a reading 13.8 hours old on a home with no Claude credential. */
class UsageFreshWindowsTest {
    @TempDir
    lateinit var tmp: Path

    private val nowMs = 1_788_000_000_000L
    private val nowS = nowMs / 1_000

    private fun usageOf(quota: QuotaView? = null, ratelimit: RateLimitView? = null): JsonObject {
        val head = UsageHead(
            key = "claude-splice",
            label = "claude-splice",
            usage = HeadUsageSource { UsageView(0, 0, ratelimit, quota) },
            warnPct = 80,
            warnTokens5h = 0,
        )
        val payloads = UsagePayloads(
            UsageHeads { listOf(head) },
            ConfigService(StatePaths(baseOverride = tmp.resolve("state"))),
            WallClock { nowMs },
        )
        return Json.parseToJsonElement(payloads.usageJson()).jsonObject
            .getValue("heads").jsonArray.single().jsonObject.getValue("usage").jsonObject
    }

    @Test
    fun `a rate-limit reading 43 hours old does not report zero percent or a current limit`() {
        val stale = RateLimitView(53_000_000L, 53_000_000L, null, nowS - 43 * 3_600L)
        val usage = usageOf(ratelimit = stale)

        assertEquals("none", usage.getValue("warn").jsonObject.getValue("source").jsonPrimitive.content)
        assertEquals("ok", usage.getValue("warn").jsonObject.getValue("level").jsonPrimitive.content)
        assertEquals("0", usage.getValue("warn").jsonObject.getValue("pct").jsonPrimitive.content)
        val observed = usage.getValue("ratelimit").jsonObject.getValue("observed_at").jsonPrimitive.content
        assertEquals("${nowS - 43 * 3_600L}", observed)
    }

    @Test
    fun `a rate-limit reading within fifteen minutes still reports its percentage`() {
        val fresh = RateLimitView(100L, 12L, "6m0s", nowS - 14 * 60)
        val usage = usageOf(ratelimit = fresh)

        assertEquals("ratelimit", usage.getValue("warn").jsonObject.getValue("source").jsonPrimitive.content)
        assertEquals("88", usage.getValue("warn").jsonObject.getValue("pct").jsonPrimitive.content)
    }

    @Test
    fun `a rate-limit observation past fifteen minutes is no longer current`() {
        val stale = RateLimitView(100L, 0L, "6m0s", nowS - 16 * 60)
        val usage = usageOf(ratelimit = stale)

        assertEquals("none", usage.getValue("warn").jsonObject.getValue("source").jsonPrimitive.content)
        assertEquals("0", usage.getValue("ratelimit").jsonObject.getValue("remaining_tokens").jsonPrimitive.content)
    }

    @Test
    fun `statusline warns only for a fresh rate-limit observation`() {
        val stale = RateLimitView(100L, 0L, null, nowS - 43 * 3_600L)
        val fresh = RateLimitView(100L, 12L, "6m0s", nowS - 14 * 60)
        val renderer = StatuslineRenderer(label = "grok", now = WallClock { nowMs })
        val body = """{"model":{"id":"grok-4.6","display_name":"Grok 4.6"}}"""
        val staleLine = renderer.render(body, HeadUsageSource { UsageView(0, 0, stale) }, 80, 0)
        val freshLine = renderer.render(body, HeadUsageSource { UsageView(0, 0, fresh) }, 80, 0)

        assertFalse("⚠" in staleLine, staleLine)
        assertTrue("⚠" in freshLine && "88%" in freshLine, freshLine)
    }

    @Test
    fun `a reading 16 minutes old is not shown and does not warn`() {
        val stale = QuotaWindowView(99, nowS + 86_400, observedAt = nowS - 16 * 60)
        val usage = usageOf(QuotaView(null, stale, "max"))

        assertNull(usage["quota"]?.jsonObject?.get("seven_day"), usage.toString())
        assertEquals("ok", usage.getValue("warn").jsonObject.getValue("level").jsonPrimitive.content)
    }

    @Test
    fun `a reading 14 minutes old is shown and warns`() {
        val fresh = QuotaWindowView(99, nowS + 86_400, observedAt = nowS - 14 * 60)
        val usage = usageOf(QuotaView(null, fresh, "max"))

        val window = usage.getValue("quota").jsonObject.getValue("seven_day").jsonObject
        assertEquals("99", window.getValue("used_pct").jsonPrimitive.content)
        assertEquals("critical", usage.getValue("warn").jsonObject.getValue("level").jsonPrimitive.content)
    }

    @Test
    fun `a window that has reset is not shown even when just read`() {
        val reset = QuotaWindowView(99, nowS - 1, observedAt = nowS - 60)
        val usage = usageOf(QuotaView(reset, null, "max"))

        assertNull(usage["quota"]?.jsonObject?.get("five_hour"), usage.toString())
    }

    @Test
    fun `a stale window beside a current one leaves only the current one`() {
        val fresh = QuotaWindowView(20, nowS + 600, observedAt = nowS - 60)
        val stale = QuotaWindowView(99, nowS + 86_400, observedAt = nowS - 3_600)
        val quota = usageOf(QuotaView(fresh, stale, "max")).getValue("quota").jsonObject

        assertEquals("20", quota.getValue("five_hour").jsonObject.getValue("used_pct").jsonPrimitive.content)
        assertNull(quota["seven_day"], quota.toString())
    }
}
