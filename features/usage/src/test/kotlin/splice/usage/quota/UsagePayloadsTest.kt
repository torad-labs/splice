// NEW: console review 2026-09-24 — /api/usage's warn reads the head's plan windows through the
// payload's clock. The console's rule bar said "no head reports a limit" while claudex held 50% of
// its 7d window, because warn had only ever read ratelimit token headers.
package splice.usage.quota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView
import splice.core.util.WallClock
import splice.usage.UsageHead
import splice.usage.UsageHeads
import java.nio.file.Path

class UsagePayloadsTest {
    @TempDir
    lateinit var tmp: Path

    private val nowMs = 1_788_000_000_000L
    private val nowS = nowMs / 1_000

    private fun warnOf(quota: QuotaView?): Map<String, String> {
        val head = UsageHead(
            key = "claudex",
            label = "claudex",
            usage = HeadUsageSource { UsageView(0, 0, null, quota) },
            warnPct = 80,
            warnTokens5h = 0,
        )
        val payloads = UsagePayloads(
            UsageHeads { listOf(head) },
            ConfigService(StatePaths(baseOverride = tmp.resolve("state"))),
            WallClock { nowMs },
        )
        val root = Json.parseToJsonElement(payloads.usageJson()).jsonObject
        val warn = root.getValue("heads").jsonArray.single().jsonObject
            .getValue("usage").jsonObject.getValue("warn").jsonObject
        return warn.mapValues { (_, v) -> v.jsonPrimitive.content }
    }

    @Test
    fun `a subscription head's warn speaks for its fullest live plan window`() {
        val warn = warnOf(QuotaView(QuotaWindowView(10, nowS + 600), QuotaWindowView(99, nowS + 86_400), "max"))
        assertEquals("critical", warn["level"])
        assertEquals("99", warn["pct"])
        assertEquals("quota_7d", warn["source"])
    }

    @Test
    fun `the payload's clock decides which windows have already reset`() {
        val warn = warnOf(QuotaView(QuotaWindowView(10, nowS + 600), QuotaWindowView(99, nowS - 1), "max"))
        assertEquals("ok", warn["level"])
        assertEquals("quota_5h", warn["source"])
    }

    @Test
    fun `a head with no plan windows and no headers still reports none`() {
        assertEquals("none", warnOf(null)["source"])
    }
}
