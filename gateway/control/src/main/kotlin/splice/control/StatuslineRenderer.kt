// PORT-OF: server/statusline/claudex-statusline.mjs @ 4ca99f7 (compact) — renders Claude Code's
// per-tick statusline from the JSON blob it pipes on stdin. Segments: model dot + name, context
// used/window · pct (colored by proximity to compaction using the head's REAL window), cache-hit
// %, and the soft-warn glyph via the shared computeUsageWarn. Degrades to a shorter line on
// missing fields; a parse failure falls back to a bare dim marker (never crashes the bar).
package splice.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.usage.RateLimitState
import splice.core.usage.computeUsageWarn

public class StatuslineRenderer(private val label: String) {
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ReturnCount")
    public fun render(stdinJson: String, usage: HeadUsageSource?, warnPct: Int, warnTokens5h: Long): String {
        val root = try {
            json.parseToJsonElement(stdinJson).jsonObject
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            return dim(label)
        }
        val parts = mutableListOf<String>()
        modelName(root)?.let { parts.add("$CYAN●$RESET $it") }
        contextSegment(root)?.let { parts.add(it) }
        cacheSegment(root)?.let { parts.add(it) }
        warnGlyph(usage, warnPct, warnTokens5h)?.let { parts.add(it) }
        return if (parts.isEmpty()) dim(label) else parts.joinToString(dim(" · "))
    }

    private fun modelName(root: JsonObject): String? =
        (root["model"] as? JsonObject)?.get("display_name")?.jsonPrimitive?.content
            ?: (root["model"] as? JsonObject)?.get("id")?.jsonPrimitive?.content

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun contextSegment(root: JsonObject): String? {
        val usageObj = root["current_usage"] as? JsonObject ?: root["usage"] as? JsonObject ?: return null
        val window = num(usageObj["context_window"]) ?: num(usageObj["context_window_size"]) ?: return null
        if (window <= 0) return null
        val used = (num(usageObj["input_tokens"]) ?: 0) +
            (num(usageObj["cache_read_input_tokens"]) ?: 0) +
            (num(usageObj["cache_creation_input_tokens"]) ?: 0)
        val pct = (used * PERCENT / window).toInt()
        val color = when {
            pct >= CRITICAL_CTX -> RED
            pct >= WARN_CTX -> YELLOW
            else -> GREEN
        }
        return "$color${fmtK(used)}/${fmtK(window)} · $pct%$RESET"
    }

    @Suppress("ReturnCount")
    private fun cacheSegment(root: JsonObject): String? {
        val usageObj = root["current_usage"] as? JsonObject ?: root["usage"] as? JsonObject ?: return null
        val input = num(usageObj["input_tokens"]) ?: return null
        if (input <= 0) return null
        val cached = num(usageObj["cache_read_input_tokens"]) ?: 0
        return dim("⚡${(cached * PERCENT / input).toInt()}%")
    }

    private fun warnGlyph(usage: HeadUsageSource?, warnPct: Int, warnTokens5h: Long): String? {
        val u = usage ?: return null
        val rl = u.ratelimit()?.let { RateLimitState(it.limitTokens, it.remainingTokens, it.resetTokens) }
        val warn = computeUsageWarn(u.outputTokens5h(), rl, warnPct, warnTokens5h)
        return when (warn.level) {
            "critical" -> "$RED⚠ ${warn.pct}%$RESET"
            "warn" -> "$YELLOW⚠ ${warn.pct}%$RESET"
            else -> null
        }
    }

    private fun num(el: kotlinx.serialization.json.JsonElement?): Long? =
        (el as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

    private fun fmtK(n: Long): String = if (n >= K) "${n / K}k" else n.toString()

    private fun dim(s: String) = "$DIM$s$RESET"

    private companion object {
        const val RESET = "\u001B[0m"
        const val DIM = "\u001B[2m"
        const val CYAN = "\u001B[36m"
        const val GREEN = "\u001B[32m"
        const val YELLOW = "\u001B[33m"
        const val RED = "\u001B[31m"
        const val PERCENT = 100
        const val CRITICAL_CTX = 90
        const val WARN_CTX = 75
        const val K = 1000
    }
}
