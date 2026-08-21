// NEW: the cache log line + UsageHud, split from TurnTelemetry (concentration, 2026-08-19)
// so the observability file is not billed for the hud's subsystem. Same-package.
package splice.gateway.head

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.Usage
import splice.gateway.usage.UsageHud

internal class TurnCacheLine(
    private val headKey: String,
) {
    private val hud = UsageHud()

    /** MOVED out of finishTurn (HD-24): a log line is telemetry. [model] is the drive's upstream
     *  model; [headKey] is the same tag every other TurnTelemetry line uses. */
    fun line(model: String, usage: Usage, compact: Boolean): String {
        val usageObj = buildJsonObject {
            put("input_tokens", usage.inputTokens)
            put("output_tokens", usage.outputTokens)
            put("input_tokens_details", buildJsonObject { put("cached_tokens", usage.cachedTokens) })
        }
        return hud.cacheLogLine(headKey, model, usageObj, compact)
    }
}
