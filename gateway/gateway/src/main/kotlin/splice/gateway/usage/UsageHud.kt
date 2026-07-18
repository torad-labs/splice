// PORT-OF: server/src/usage/hud.mjs @ 4ca99f7 — invariants: buildUsagePayload stuffs the
// NON-STANDARD fields Claude Code reads from custom gateways (context_window,
// context_window_size, used_percentage) sized from the head's REAL window; accepts Anthropic
// names and OpenAI Responses aliases (prompt/completion, input_tokens_details.cached_tokens);
// makeOutputClamp clamps REPORTED output to the client's max_tokens (backend rejects cap
// params; reasoning tokens count in output — v26); logTurnCache's exact line format is
// watchable via log tail; usage/ratelimit state files are the HUD contract. SEAM (recorded):
// log lines are injected writers; appends are synchronous best-effort (Node used microtasks).
package splice.gateway.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.usage.RateLimitState
import splice.core.util.runCatchingCancellable
import java.nio.file.Files
import java.nio.file.Path

private const val FIVE_HOURS_MS: Long = 5 * 60 * 60 * 1000
private const val FULL_PCT = 100.0

// output_tokens is the one usage field name written from several sites; naming it once keeps the
// wire contract single-sourced (the others stay inline — they don't repeat enough to warrant it).
private const val OUTPUT_TOKENS = "output_tokens"

private fun num(el: JsonElement?): Long? =
    (el as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

/** First key whose value parses as a number — the ported alias-fallback chain, single-sourced. */
private fun JsonObject.firstNum(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { num(this[it]) }

/** Usage aliases: Anthropic names + OpenAI Responses names + cached-token detail. */
public data class TurnUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationInputTokens: Long,
    val cacheReadInputTokens: Long,
) {
    public companion object {
        public fun from(usage: JsonObject?): TurnUsage {
            val u = usage ?: JsonObject(emptyMap())
            val cachedDetail = (u["input_tokens_details"] as? JsonObject)?.let { num(it["cached_tokens"]) }
            return TurnUsage(
                inputTokens = u.firstNum("input_tokens", "prompt_tokens") ?: 0,
                outputTokens = u.firstNum(OUTPUT_TOKENS, "completion_tokens") ?: 0,
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
        put(OUTPUT_TOKENS, usage.outputTokens)
        put("cache_creation_input_tokens", usage.cacheCreationInputTokens)
        put("cache_read_input_tokens", usage.cacheReadInputTokens)
        if (contextWindow != null && contextWindow > 0) {
            put("context_window", contextWindow)
            put("context_window_size", contextWindow)
            put("used_percentage", totalInput.toDouble() / contextWindow * FULL_PCT)
        }
    }
}

/** One concise line per completed turn so the cache hit rate is watchable live. Parses via the
 *  SAME [TurnUsage.from] the payload uses — a second inline parser here had drifted to the OPPOSITE
 *  cached-token precedence, so the logged hit-rate could disagree with the wire (craft review). */
public fun cacheLogLine(headTag: String, model: String, usage: JsonObject?, compact: Boolean): String {
    val u = TurnUsage.from(usage)
    val cached = u.cacheReadInputTokens
    val pct = if (u.inputTokens > 0) (cached.toDouble() / u.inputTokens * FULL_PCT).toInt() else 0
    val compactSuffix = if (compact) " compact" else ""
    return "[$headTag] cache: input=${u.inputTokens} cached=$cached hit=$pct% " +
        "output=${u.outputTokens}$compactSuffix model=$model\n"
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

    // best-effort by design: I/O failure leaves the last-known window untouched; cancellation
    // still propagates via runCatchingCancellable. In-memory ring keeps the 5h window so a
    // busy head does not rewrite+reparse the whole JSON array on every completed turn. ALL ring
    // mutation/iteration happens under ringLock (append vs statusline reads raced a CME and
    // dropped counts — audit 2026-07-18); the file write happens OUTSIDE the lock from an
    // immutable snapshot so slow disks never block the statusline tick.
    public fun appendOutputTokens(outputTokens: Long) {
        if (outputTokens <= 0) return
        runCatchingCancellable {
            Files.createDirectories(usageFile.parent)
            val now = clock()
            val snapshot = synchronized(ringLock) {
                val ring = loadRingUnderLock(now - FIVE_HOURS_MS)
                ring.addLast(
                    buildJsonObject {
                        put("timestamp", now)
                        put(OUTPUT_TOKENS, outputTokens)
                    },
                )
                // Cap ring growth even if the clock stalls (defensive).
                while (ring.size > MAX_RING_ENTRIES) ring.removeFirst()
                ring.toList()
            }
            Files.writeString(usageFile, buildJsonArray { snapshot.forEach { add(it) } }.toString())
        }
    }

    /** Parses x-ratelimit-limit-tokens / -remaining-tokens / -reset-tokens; no-op without a limit. */
    // best-effort by design: header/write failures are swallowed; cancellation propagates.
    public fun persistRateLimit(header: (String) -> String?) {
        runCatchingCancellable {
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
        }
    }

    public fun readState(): UsageState {
        val cutoff = clock() - FIVE_HOURS_MS
        val (entries, tokens) = synchronized(ringLock) {
            val ring = loadRingUnderLock(cutoff)
            ring.size to ring.sumOf { num(it[OUTPUT_TOKENS]) ?: 0 }
        }
        return UsageState(
            windowHours = 5,
            entries = entries,
            outputTokens5h = tokens,
            ratelimit = readRateLimit(),
        )
    }

    // best-effort by design: a missing/corrupt ratelimit file reads as null; cancellation propagates.
    public fun readRateLimit(): RateLimitState? = runCatchingCancellable {
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
    }.getOrNull()

    /**
     * Return the live 5h ring, loading from disk only on first use (or after a process restart).
     * Entries older than [cutoff] are dropped. CALLERS HOLD [ringLock] — the deque itself must
     * never escape the lock (mutation + iteration outside it was the audit's CME finding).
     */
    private fun loadRingUnderLock(cutoff: Long): ArrayDeque<JsonObject> {
        if (!ringLoaded) {
            cachedRing.clear()
            cachedRing.addAll(readEntriesFromDisk())
            ringLoaded = true
        }
        while (cachedRing.isNotEmpty() && (num(cachedRing.first()["timestamp"]) ?: 0) <= cutoff) {
            cachedRing.removeFirst()
        }
        return cachedRing
    }

    // best-effort by design: a missing/corrupt usage file reads as empty; cancellation propagates.
    // The file is a JSON array rewritten on every append (not JSONL). Growth is bounded by the
    // 5h window filter + MAX_RING_ENTRIES; oversize files are treated as empty.
    private fun readEntriesFromDisk(): List<JsonObject> = runCatchingCancellable {
        if (!Files.exists(usageFile)) {
            emptyList()
        } else {
            val size = Files.size(usageFile)
            if (size > MAX_USAGE_FILE_BYTES) {
                emptyList()
            } else {
                json.parseToJsonElement(Files.readString(usageFile)).jsonArray.mapNotNull { it as? JsonObject }
            }
        }
    }.getOrDefault(emptyList())

    private val ringLock = Any()
    private val cachedRing = ArrayDeque<JsonObject>()
    private var ringLoaded = false

    private companion object {
        // MUST comfortably exceed MAX_RING_ENTRIES x ~50 bytes/row — at 2MB the reader treated a
        // legitimately capped ring file (~2.25MB) as corrupt and DROPPED the whole live window on
        // restart (audit 2026-07-18). 8MB keeps the corrupt-file guard with real headroom.
        const val MAX_USAGE_FILE_BYTES = 8L * 1024 * 1024

        // ~1 entry/sec for 5h would be 18k; 50k is a hard safety cap against clock stalls.
        const val MAX_RING_ENTRIES = 50_000
    }
}
