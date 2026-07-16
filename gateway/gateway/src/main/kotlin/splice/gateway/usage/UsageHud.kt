// PORT-OF: server/src/usage/hud.mjs @ 4ca99f7 — invariants: buildUsagePayload stuffs the
// NON-STANDARD fields Claude Code reads from custom gateways (context_window,
// context_window_size, used_percentage) sized from the head's REAL window; accepts Anthropic
// names and OpenAI Responses aliases (prompt/completion, input_tokens_details.cached_tokens);
// makeOutputClamp clamps REPORTED output to the client's max_tokens (backend rejects cap
// params; reasoning tokens count in output — v26); logTurnCache's exact line format is
// watchable via log tail; usage/ratelimit state files are the HUD contract. SEAM (recorded):
// log lines are injected writers; appends are synchronous best-effort (Node used microtasks).
@file:Suppress("StringLiteralDuplication") // usage field names are the wire contract

package splice.gateway.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

private const val FIVE_HOURS_MS: Long = 5 * 60 * 60 * 1000
private const val FULL_PCT = 100.0

private fun num(el: kotlinx.serialization.json.JsonElement?): Long? =
    (el as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

/** Usage aliases: Anthropic names + OpenAI Responses names + cached-token detail. */
public data class TurnUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
) {
    public companion object {
        @Suppress("CyclomaticComplexMethod") // the alias fallback chain is the ported contract
        public fun from(usage: JsonObject?): TurnUsage {
            val u = usage ?: JsonObject(emptyMap())
            val cachedDetail = (u["input_tokens_details"] as? JsonObject)?.let { num(it["cached_tokens"]) }
            return TurnUsage(
                inputTokens = num(u["input_tokens"]) ?: num(u["prompt_tokens"]) ?: 0,
                outputTokens = num(u["output_tokens"]) ?: num(u["completion_tokens"]) ?: 0,
                cacheCreationInputTokens = num(u["cache_creation_input_tokens"]) ?: 0,
                cacheReadInputTokens = num(u["cache_read_input_tokens"]) ?: cachedDetail ?: 0,
            )
        }
    }
}

/** The gateway usage payload with Claude Code's non-standard context fields. */
public fun buildUsagePayload(usage: TurnUsage, contextWindow: Long?): JsonObject {
    val totalInput = usage.inputTokens + usage.cacheCreationInputTokens + usage.cacheReadInputTokens
    return buildJsonObject {
        put("input_tokens", usage.inputTokens)
        put("output_tokens", usage.outputTokens)
        put("cache_creation_input_tokens", usage.cacheCreationInputTokens)
        put("cache_read_input_tokens", usage.cacheReadInputTokens)
        if (contextWindow != null && contextWindow > 0) {
            put("context_window", contextWindow)
            put("context_window_size", contextWindow)
            put("used_percentage", totalInput.toDouble() / contextWindow * FULL_PCT)
        }
    }
}

/** One concise line per completed turn so the cache hit rate is watchable live. */
@Suppress("CyclomaticComplexMethod") // alias fallbacks
public fun cacheLogLine(headTag: String, model: String, usage: JsonObject?, compact: Boolean): String {
    val u = usage ?: JsonObject(emptyMap())
    val input = num(u["input_tokens"]) ?: num(u["prompt_tokens"]) ?: 0
    val cached = (u["input_tokens_details"] as? JsonObject)?.let { num(it["cached_tokens"]) }
        ?: num(u["cache_read_input_tokens"]) ?: 0
    val output = num(u["output_tokens"]) ?: num(u["completion_tokens"]) ?: 0
    val pct = if (input > 0) (cached.toDouble() / input * FULL_PCT).toInt() else 0
    val compactSuffix = if (compact) " compact" else ""
    return "[$headTag] cache: input=$input cached=$cached hit=$pct% output=$output$compactSuffix model=$model\n"
}

/** Clamp REPORTED output_tokens to the client's max_tokens (v26). */
public fun makeOutputClamp(
    clientMaxTokens: Long?,
    compact: Boolean,
    headTag: String,
    log: (String) -> Unit,
): (Long) -> Long {
    val max = clientMaxTokens?.takeIf { it > 0 }
    return { n ->
        if (max != null && n > max) {
            log("[$headTag] output_tokens $n > client max_tokens $max compact=$compact — clamping reported usage\n")
            max
        } else {
            n
        }
    }
}

public data class UsageState(
    val windowHours: Int,
    val entries: Int,
    val outputTokens5h: Long,
    val ratelimit: RateLimitState?,
)

/** 5h output-token window + ratelimit header persistence — the HUD contract files. */
public class UsageStore(
    private val usageFile: Path,
    private val ratelimitFile: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // best-effort by design
    public fun appendOutputTokens(outputTokens: Long) {
        if (outputTokens <= 0) return
        try {
            Files.createDirectories(usageFile.parent)
            val cutoff = clock() - FIVE_HOURS_MS
            val kept = readEntries().filter { (num(it["timestamp"]) ?: 0) > cutoff }
            val next = buildJsonArray {
                kept.forEach { add(it) }
                add(
                    buildJsonObject {
                        put("timestamp", clock())
                        put("output_tokens", outputTokens)
                    },
                )
            }
            Files.writeString(usageFile, next.toString())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    /** Parses x-ratelimit-limit-tokens / -remaining-tokens / -reset-tokens; no-op without a limit. */
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // best-effort by design
    public fun persistRateLimit(header: (String) -> String?) {
        try {
            val limit = header("x-ratelimit-limit-tokens")?.toLongOrNull() ?: return
            val remaining = header("x-ratelimit-remaining-tokens")?.toLongOrNull()
            val reset = header("x-ratelimit-reset-tokens")?.takeIf { it.isNotEmpty() }
            Files.createDirectories(ratelimitFile.parent)
            val payload = buildJsonObject {
                put("limit_tokens", limit)
                // NB: JsonObjectBuilder.put returns the PREVIOUS value (null on first insert) —
                // an elvis on it double-puts. Explicit branches only.
                if (remaining != null) put("remaining_tokens", remaining) else put("remaining_tokens", null as String?)
                if (reset != null) put("reset_tokens", reset) else put("reset_tokens", null as String?)
                put("updated_at", clock())
            }
            Files.writeString(ratelimitFile, payload.toString() + "\n")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    public fun readState(): UsageState {
        val cutoff = clock() - FIVE_HOURS_MS
        val window = readEntries().filter { (num(it["timestamp"]) ?: 0) > cutoff }
        return UsageState(
            windowHours = 5,
            entries = window.size,
            outputTokens5h = window.sumOf { num(it["output_tokens"]) ?: 0 },
            ratelimit = readRateLimit(),
        )
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // best-effort by design
    public fun readRateLimit(): RateLimitState? = try {
        if (!Files.exists(ratelimitFile)) {
            null
        } else {
            val obj = json.parseToJsonElement(Files.readString(ratelimitFile)).jsonObject
            RateLimitState(
                limitTokens = num(obj["limit_tokens"]),
                remainingTokens = num(obj["remaining_tokens"]),
                resetTokens = (obj["reset_tokens"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                updatedAt = num(obj["updated_at"]),
            )
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException") // best-effort by design
    private fun readEntries(): List<JsonObject> = try {
        if (!Files.exists(usageFile)) {
            emptyList()
        } else {
            json.parseToJsonElement(Files.readString(usageFile)).jsonArray.mapNotNull { it as? JsonObject }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        emptyList()
    }
}
