// PORT-OF: ControlServer.kt (ControlPayloads.usageJson) @ a77531a — invariants unchanged: the
// per-head usage/warn projection, split out as the sole importer of splice.core.usage in the file.
package splice.usage.quota

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.accounts.pool.AccountPoolJson
import splice.core.config.ConfigService
import splice.core.usage.PlanWindows
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindowView
import splice.core.usage.RateLimitState
import splice.core.usage.UsageWarnPolicy
import splice.core.util.WallClock
import splice.usage.UsageHeads
import kotlin.time.Duration.Companion.milliseconds

private const val KEY = "key"
private const val LABEL = "label"
private const val HEADS = "heads"
private const val USAGE_WINDOW_HOURS = 5

public class UsagePayloads(
    private val heads: UsageHeads,
    private val config: ConfigService,
    /** Decides which plan windows have already reset when warn reads them. */
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val accountJson = AccountPoolJson()

    // PORT-OF server/src/control/api.mjs usage payload @ pre-public-port-baseline: top-level window/warn knobs +
    // per-head {key,label,usage:{output_tokens_5h,entries,ratelimit,warn}} (webui UsagePayload).
    public fun usageJson(): String {
        val cfg = config.getConfig()
        return buildJsonObject {
            put("window_hours", USAGE_WINDOW_HOURS)
            put("warn_pct", cfg.usageWarnPct)
            put("warn_tokens_5h", cfg.usageWarnTokens5h)
            putJsonArray(HEADS) {
                heads.all().forEach { m ->
                    val usage = m.usage.snapshot()
                    val pool = m.accountPool?.view(null)
                    val selectedQuota = pool?.selectedQuota() ?: usage.quota
                    val rlView = usage.ratelimit
                    val rl = rlView?.let { RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens) }
                    val plan = selectedQuota?.let { PlanWindows(it, clock().milliseconds.inWholeSeconds) }
                    val warn =
                        UsageWarnPolicy.computeUsageWarn(usage.outputTokens5h, rl, m.warnPct, m.warnTokens5h, plan)
                    addJsonObject {
                        put(KEY, m.key)
                        put(LABEL, m.label)
                        pool?.let { accountJson.write(this, it) }
                        putJsonObject("usage") {
                            put("output_tokens_5h", usage.outputTokens5h)
                            put("entries", usage.entries)
                            selectedQuota?.let { q -> quota(this, q) }
                            if (rlView != null) {
                                putJsonObject("ratelimit") {
                                    put("limit_tokens", rlView.limitTokens)
                                    put("remaining_tokens", rlView.remainingTokens)
                                    put("reset_tokens", rlView.resetTokens)
                                }
                            } else {
                                put("ratelimit", null as String?)
                            }
                            putJsonObject("warn") {
                                put("level", warn.level)
                                put("pct", warn.pct)
                                put("source", warn.source)
                                put("reset", warn.reset)
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    /** The head's tracked plan windows (see QuotaTracker): `{plan, five_hour, seven_day}`. */
    private fun quota(into: JsonObjectBuilder, q: QuotaView) {
        into.putJsonObject("quota") {
            q.plan?.let { put("plan", it) }
            q.fiveHour?.let { w -> window(this, "five_hour", w) }
            q.sevenDay?.let { w -> window(this, "seven_day", w) }
        }
    }

    private fun window(into: JsonObjectBuilder, name: String, w: QuotaWindowView) {
        into.putJsonObject(name) {
            put("used_pct", w.usedPct)
            put("resets_at", w.resetsAt)
        }
    }
}
