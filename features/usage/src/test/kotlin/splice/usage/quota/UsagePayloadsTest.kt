// NEW: GET /api/usage's `observed_at`, at the payload boundary. The daemon records when each quota
// window and each rate-limit read was observed; before this field the payload dropped it, so a bar
// the head last saw days ago read exactly like a live one. Both objects carry it in the encoding the
// quota windows' `resets_at` uses (epoch seconds), and a source that names no observation is a JSON
// null, never an absent key and never 0.
package splice.usage.quota

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.pool.HeadAccountPoolSource
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView
import splice.usage.UsageHead
import splice.usage.UsageHeads
import java.nio.file.Path

private const val OBSERVED = 1_789_310_000L
private const val FIVE_RESET = 1_789_320_000L
private const val SEVEN_RESET = 1_789_900_000L

class UsagePayloadsTest {
    @TempDir
    lateinit var tmp: Path

    private fun usage(view: UsageView, pool: HeadAccountPoolSource? = null): JsonObject {
        val head = UsageHead(
            key = "codex",
            label = "codex",
            usage = HeadUsageSource { view },
            warnPct = 80,
            warnTokens5h = 0,
            accountPool = pool,
        )
        val payloads = UsagePayloads(UsageHeads { listOf(head) }, ConfigService(StatePaths(baseOverride = tmp)))
        val root = Json.parseToJsonElement(payloads.usageJson()).jsonObject
        return root.getValue("heads").jsonArray.single().jsonObject.getValue("usage").jsonObject
    }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    @Test
    fun `every quota window and the ratelimit carry observed_at in the resets_at encoding`() {
        val quota = QuotaView(
            QuotaWindowView(40, FIVE_RESET, OBSERVED),
            QuotaWindowView(9, SEVEN_RESET, OBSERVED),
            "pro",
        )
        val u = usage(UsageView(0L, 1, RateLimitView(1000, 100, "6m0s", OBSERVED + 5), quota))
        val five = u.obj("quota").obj("five_hour")
        assertEquals(FIVE_RESET.toString(), five.getValue("resets_at").jsonPrimitive.content)
        assertEquals(OBSERVED.toString(), five.getValue("observed_at").jsonPrimitive.content, "epoch seconds")
        assertEquals(OBSERVED.toString(), u.obj("quota").obj("seven_day").getValue("observed_at").jsonPrimitive.content)
        val ratelimit = u.obj("ratelimit")
        assertEquals((OBSERVED + 5).toString(), ratelimit.getValue("observed_at").jsonPrimitive.content)
        assertEquals("6m0s", ratelimit.getValue("reset_tokens").jsonPrimitive.content, "the siblings are untouched")
    }

    @Test
    fun `no observation is a present JSON null, never an absent key or 0`() {
        val quota = QuotaView(QuotaWindowView(40, null), null, null)
        val u = usage(UsageView(0L, 1, RateLimitView(1000, 100, null), quota))
        val five = u.obj("quota").obj("five_hour")
        assertTrue(five.containsKey("observed_at"), "the console reads a key, so it is always written")
        assertEquals(JsonNull, five["observed_at"])
        assertEquals(JsonNull, u.obj("ratelimit")["observed_at"])
        assertEquals(JsonNull, usage(UsageView(0L, 0, null))["ratelimit"], "no read at all stays a null ratelimit")
    }

    @Test
    fun `a pooled head's windows carry the SELECTED account's observation`() {
        val selected = account("work", selected = true, observed = OBSERVED)
        val other = account("home", selected = false, observed = OBSERVED - 3_600)
        val pool = HeadAccountPoolSource { HeadAccountPoolView("work", listOf(other, selected), null) }
        val tracked = QuotaView(QuotaWindowView(1, FIVE_RESET, 1L), null, null)
        val u = usage(UsageView(0L, 1, null, tracked), pool)
        val five = u.obj("quota").obj("five_hour")
        assertEquals("40", five.getValue("used_pct").jsonPrimitive.content, "the pool's window, not the tracker's")
        assertEquals(OBSERVED.toString(), five.getValue("observed_at").jsonPrimitive.content)
        assertEquals(OBSERVED.toString(), u.obj("quota").obj("seven_day").getValue("observed_at").jsonPrimitive.content)
    }

    private fun account(label: String, selected: Boolean, observed: Long) = HeadAccountView(
        label = label,
        primary = !selected,
        selected = selected,
        available = true,
        plan = "pro",
        fiveHourUsedPercent = 40.0,
        fiveHourResetEpochSeconds = FIVE_RESET,
        sevenDayUsedPercent = 9.0,
        sevenDayResetEpochSeconds = SEVEN_RESET,
        quotaObservedAtEpochSeconds = observed,
    )
}
