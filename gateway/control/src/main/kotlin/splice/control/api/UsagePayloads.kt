// PORT-OF: ControlServer.kt (ControlPayloads.usageJson) @ a77531a — invariants unchanged: the
// per-head usage/warn projection, split out as the sole importer of splice.core.usage in the file.
package splice.control.api

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.ManagedHead
import splice.core.config.ConfigService
import splice.core.usage.RateLimitState
import splice.core.usage.UsageWarnPolicy

private const val KEY = "key"
private const val LABEL = "label"
private const val HEADS = "heads"
private const val USAGE_WINDOW_HOURS = 5

internal class UsagePayloads(
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
) {
    // PORT-OF server/src/control/api.mjs usage payload @ pre-public-port-baseline: top-level window/warn knobs +
    // per-head {key,label,usage:{output_tokens_5h,entries,ratelimit,warn}} (webui UsagePayload).
    fun usageJson(): String {
        val cfg = config.getConfig()
        return buildJsonObject {
            put("window_hours", USAGE_WINDOW_HOURS)
            put("warn_pct", cfg.usageWarnPct)
            put("warn_tokens_5h", cfg.usageWarnTokens5h)
            putJsonArray(HEADS) {
                heads.values.forEach { m ->
                    val usage = m.usage.snapshot()
                    val rlView = usage.ratelimit
                    val rl = rlView?.let { RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens) }
                    val warn = UsageWarnPolicy.computeUsageWarn(usage.outputTokens5h, rl, m.warnPct, m.warnTokens5h)
                    addJsonObject {
                        put(KEY, m.head.key)
                        put(LABEL, m.head.label)
                        putJsonObject("usage") {
                            put("output_tokens_5h", usage.outputTokens5h)
                            put("entries", usage.entries)
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
}
