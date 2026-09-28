package splice.usage.quota.v4396

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import splice.usage.quota.UsagePayloads
import splice.usage.quota.UsageView
import java.nio.file.Path

/** V4-396: /api/usage shows a plan window only while it is current — read under 15 minutes ago and
 *  not yet reset — the rule the status line already applies (QuotaFreshness). Marlin's header read
 *  "claude-splice 7d 99%" from a reading 13.8 hours old on a home with no Claude credential. */
class UsageFreshWindowsTest {
    @TempDir
    lateinit var tmp: Path

    private val nowMs = 1_788_000_000_000L
    private val nowS = nowMs / 1_000

    private fun usageOf(quota: QuotaView): JsonObject {
        val head = UsageHead(
            key = "claude-splice",
            label = "claude-splice",
            usage = HeadUsageSource { UsageView(0, 0, null, quota) },
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
