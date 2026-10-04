// PORT-OF: ControlServer.kt (ControlPayloads.usageJson) @ a77531a — invariants unchanged: the
// per-head usage/warn projection, split out as the sole importer of splice.core.usage in the file.
package splice.usage.quota

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import splice.core.usage.UsageWarn
import splice.core.usage.UsageWarnPolicy
import splice.core.util.WallClock
import splice.usage.UsageHeads
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.milliseconds

private const val KEY = "key"
private const val LABEL = "label"
private const val HEADS = "heads"
private const val USAGE_WINDOW_HOURS = 5

// A provider refusal until a known instant is a fully spent window: the warn reads 100 percent.
private const val FULL_PERCENT = 100

/** When a quota window or the rate-limit read was observed, epoch SECONDS — the encoding the quota
 *  windows' `resets_at` uses, on both objects, so the console reads one instant format. */
private const val OBSERVED_AT = "observed_at"

public class UsagePayloads(
    private val heads: UsageHeads,
    private val config: ConfigService,
    /** Decides which plan windows have already reset when warn reads them. */
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val accountJson = AccountPoolJson()

    /** Probe every wired head, sharing each account's poller admission, then read the resulting file truth. */
    public suspend fun probeNowJson(): String {
        coroutineScope { heads.all().map { head -> async { head.usage.probeNow() } }.awaitAll() }
        return usageJson()
    }

    // PORT-OF server/src/control/api.mjs usage payload @ pre-public-port-baseline: top-level window/warn knobs +
    // per-head {key,label,usage:{output_tokens_5h,entries,ratelimit,warn}} (webui UsagePayload).
    public fun usageJson(): String {
        val cfg = config.getConfig()
        return buildJsonObject {
            put("window_hours", USAGE_WINDOW_HOURS)
            put("warn_pct", cfg.usageWarnPct)
            put("warn_tokens_5h", cfg.usageWarnTokens5h)
            putJsonArray(HEADS) {
                val nowSeconds = clock().milliseconds.inWholeSeconds
                heads.all().forEach { m ->
                    val usage = m.usage.snapshot()
                    val pool = m.accountPool?.view(null)
                    val selectedQuota = current(pool?.selectedQuota(), nowSeconds) ?: current(usage.quota, nowSeconds)
                    val rlView = usage.ratelimit
                    val rl = rlView?.currentAt(nowSeconds)?.let {
                        RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens)
                    }
                    val plan = selectedQuota?.let { PlanWindows(it, nowSeconds) }
                    val warn = refusal(m.key)
                        ?: UsageWarnPolicy.computeUsageWarn(usage.outputTokens5h, rl, m.warnPct, m.warnTokens5h, plan)
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
                                    put(OBSERVED_AT, rlView.observedAt)
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

    /** V4-398: a provider that refuses until a known instant is fully spent whatever the headers or
     *  plan windows last said, so it outranks every other tier; null once no reset is pending. */
    private fun refusal(key: String): UsageWarn? {
        val remainingMs = heads.providerResetForMs(key).takeIf { it > 0L } ?: return null
        val reset = Instant.ofEpochMilli(clock() + remainingMs).truncatedTo(ChronoUnit.SECONDS)
        return UsageWarn("critical", FULL_PERCENT, "provider_reset", reset.toString())
    }

    /** V4-396: the windows a surface may show at [nowSeconds], through [QuotaWindowView.currentAt]
     *  (QuotaFreshness: read under 15 minutes ago and not yet reset), the rule the status line
     *  already reads; null when neither is current, so a pooled head falls through to its tracked
     *  quota and a head with no current reading shows none. */
    private fun current(quota: QuotaView?, nowSeconds: Long): QuotaView? {
        if (quota == null) return null
        val fiveHour = quota.fiveHour?.currentAt(nowSeconds)
        val sevenDay = quota.sevenDay?.currentAt(nowSeconds)
        return if (fiveHour == null && sevenDay == null) null else QuotaView(fiveHour, sevenDay, quota.plan)
    }

    /** The head's tracked plan windows (see QuotaTracker): `{plan, five_hour, seven_day}`. */
    private fun quota(into: JsonObjectBuilder, q: QuotaView) {
        into.putJsonObject("quota") {
            q.plan?.let { put("plan", it) }
            q.fiveHour?.let { w -> window(this, "five_hour", w) }
            q.sevenDay?.let { w -> window(this, "seven_day", w) }
        }
    }

    /** `{used_pct, resets_at, observed_at}`: both instants epoch SECONDS, each null when unknown. */
    private fun window(into: JsonObjectBuilder, name: String, w: QuotaWindowView) {
        into.putJsonObject(name) {
            put("used_pct", w.usedPct)
            put("resets_at", w.resetsAt)
            put(OBSERVED_AT, w.observedAt)
        }
    }
}
