// NEW: (quota instrument, 2026-08-01) the hourly token-economics rollup.
//
// WHY THIS EXISTS AND UsageStore DOES NOT SUFFICE. UsageStore rings OUTPUT tokens over 5h. The
// plan meters TOTAL INPUT — cache hits INCLUDED — and nothing in splice was counting that.
// Measured across two independent exhaustion windows (daemon.log, 2026-08-01):
//
//     window          uncached      TOTAL input     plan reported
//     Jul 28+29         571M          1.716B            97%
//     Jul 30+31→11:59   390M          1.759B           100%
//
// Total input predicts the meter (~1.77B ⇒ 100%); uncached does not — the SMALLER 390M hit 100%
// while the LARGER 571M sat at 97%. So a 90%-cached 150k prompt bills as a 150k prompt, and the
// cache hit rate, which splice already logs, is the wrong number to watch for quota. This store
// counts the right one.
//
// WHY NOT AGGREGATE THE PERF JSONL. It carries every field needed, but claudex-perf.jsonl is 53MB
// and PerfStats.tailNumeric is bounded to a 256KB tail (~500 rows) — a weekly SUM is not
// recoverable from p50/p95/max over the last few hundred turns, and re-reading 53MB per dashboard
// poll is not an option. This is an O(1)-per-turn accumulator instead: hourly buckets, 8 days
// retained (192 rows, single-digit KB).
//
// SHAPE MIRRORS UsageStore deliberately — same bounded file lane, same coalesced debounce, same
// atomic replace, same best-effort doctrine. A turn must never pay for, nor fail on, telemetry.
package splice.gateway.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import splice.core.perf.ECONOMICS_RETENTION_MS
import splice.core.util.Cancellables
import splice.core.util.CoalescedFlush
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private const val HOUR_MS = 60L * 60 * 1000

// V4-122: RETENTION_MS is splice.core.perf.ECONOMICS_RETENTION_MS now. The console reports the same
// window in HOURS from :control/api, which has no dependency edge to this module, so the comment
// that used to claim the two mirrored each other is replaced by one declaration both can read.

// 192 buckets x ~200 bytes is single-digit KB; 1MB is a corrupt-file guard with headroom.
private const val MAX_FILE_BYTES = 1L * 1024 * 1024
private const val ECONOMICS_FLUSH_DELAY_MS = 1_000L

/** One hour of a head's economics. Sums only — ratios are derived by the reader, never stored,
 *  so a bucket stays mergeable and a rounding choice never hardens into the file. */
public data class EconomicsBucket(
    val hour: Long,
    val turns: Long = 0,
    val inTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** V4-86: the cache-WRITE half of [inTokens], disjoint from [cachedTokens] (the read half).
     *  Recorded BESIDE input, never out of it, for the same reason [cachedTokens] is: the plan
     *  meters total input and a written block bills in full. It is a separate sum because it
     *  bills at the vendor's cache_write rate, not the input rate. */
    val cacheWriteTokens: Long = 0,
    val outTokens: Long = 0,
    val reqBytes: Long = 0,
    val upstreamBytes: Long = 0,
    val toolsEager: Long = 0,
    val toolsDeferred: Long = 0,
    val deferralTurns: Long = 0,
    val rateLimited: Long = 0,
)

/** The per-turn facts the rollup consumes. Nullable where a head genuinely may not report the
 *  field: the chat dialect has no tool deferral at all, and `null` must stay distinguishable from
 *  a real zero — "this head cannot defer" and "this head deferred nothing" are different findings. */
public data class TurnEconomics(
    val inTokens: Long,
    val cachedTokens: Long,
    /** V4-86: this turn's cache-write bucket, from PerfKeys.CACHE_WRITE_TOKENS. NO default on
     *  purpose — a default would let a new call site drop the most expensive bucket on the turn
     *  and still compile, which is exactly how the counter came to die at this seam. */
    val cacheWriteTokens: Long,
    val outTokens: Long,
    val reqBytes: Long?,
    val upstreamBytes: Long?,
    val toolsEager: Long?,
    val toolsDeferred: Long?,
    val rateLimited: Boolean = false,
)

public class EconomicsStore(
    private val file: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fold one finished turn into its hour. Memory-only plus an enqueue — never blocks the turn. */
    public fun record(turn: TurnEconomics) {
        val hour = clock() / HOUR_MS * HOUR_MS
        synchronized(lock) {
            loadUnderLock()
            val b = buckets[hour] ?: EconomicsBucket(hour)
            buckets[hour] = b.copy(
                turns = b.turns + 1,
                inTokens = b.inTokens + turn.inTokens,
                cachedTokens = b.cachedTokens + turn.cachedTokens,
                cacheWriteTokens = b.cacheWriteTokens + turn.cacheWriteTokens,
                outTokens = b.outTokens + turn.outTokens,
                reqBytes = b.reqBytes + (turn.reqBytes ?: 0),
                upstreamBytes = b.upstreamBytes + (turn.upstreamBytes ?: 0),
                toolsEager = b.toolsEager + (turn.toolsEager ?: 0),
                toolsDeferred = b.toolsDeferred + (turn.toolsDeferred ?: 0),
                // Only turns that actually REPORTED a partition count toward the deferral average,
                // so a head that cannot defer averages over zero turns and reads as "—" rather than
                // being diluted to a misleading 0.0 by turns that never had the choice.
                deferralTurns = b.deferralTurns + if (turn.toolsEager != null) 1 else 0,
                rateLimited = b.rateLimited + if (turn.rateLimited) 1 else 0,
            )
            trimUnderLock()
            version += 1
        }
        CoalescedFlush.scheduleCoalesced(ECONOMICS_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
    }

    /** Buckets inside the retention window, oldest first. */
    public fun read(): List<EconomicsBucket> = synchronized(lock) {
        loadUnderLock()
        trimUnderLock()
        buckets.values.sortedBy { it.hour }
    }

    /** Force the newest snapshot to stable storage (head stop and deterministic tests). */
    public fun flushNow() {
        val (snapshot, v) = synchronized(lock) { buckets.values.sortedBy { it.hour } to version }
        persist(snapshot, v)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private val lock = Any()
    private val writeLock = Any()
    private val buckets = LinkedHashMap<Long, EconomicsBucket>()
    private var loaded = false
    private var version = 0L

    @Volatile
    private var persistedVersion = -1L
    private val writeScheduled = AtomicBoolean(false)

    private fun loadUnderLock() {
        if (loaded) return
        loaded = true
        readFromDisk().forEach { buckets[it.hour] = it }
    }

    private fun trimUnderLock() {
        val cutoff = clock() - ECONOMICS_RETENTION_MS
        buckets.keys.filter { it < cutoff }.forEach { buckets.remove(it) }
    }

    // best-effort by design: a missing/corrupt file reads as empty; cancellation propagates.
    private fun readFromDisk(): List<EconomicsBucket> = Cancellables.runCatchingCancellable {
        if (!Files.exists(file) || Files.size(file) > MAX_FILE_BYTES) {
            emptyList()
        } else {
            json.parseToJsonElement(Files.readString(file)).jsonArray
                .mapNotNull { (it as? JsonObject)?.let(::bucketFrom) }
        }
    }.getOrDefault(emptyList())

    private fun flushScheduled() {
        val (snapshot, v) = synchronized(lock) { buckets.values.sortedBy { it.hour } to version }
        persist(snapshot, v)
        writeScheduled.set(false)
        if (synchronized(lock) { version > persistedVersion }) {
            CoalescedFlush.scheduleCoalesced(ECONOMICS_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
        }
    }

    private fun persist(snapshot: List<EconomicsBucket>, v: Long) {
        synchronized(writeLock) {
            if (v <= persistedVersion) return
            val encoded = buildJsonArray {
                snapshot.forEach { b ->
                    add(
                        buildJsonObject {
                            put("hour", b.hour)
                            put("turns", b.turns)
                            put("in_tokens", b.inTokens)
                            put("cached_tokens", b.cachedTokens)
                            put("cache_write_tokens", b.cacheWriteTokens)
                            put("out_tokens", b.outTokens)
                            put("req_bytes", b.reqBytes)
                            put("upstream_req_bytes", b.upstreamBytes)
                            put("tools_eager", b.toolsEager)
                            put("tools_deferred", b.toolsDeferred)
                            put("deferral_turns", b.deferralTurns)
                            put("rate_limited", b.rateLimited)
                        },
                    )
                }
            }.toString() + "\n"
            if (Cancellables.runCatchingCancellable { SecureFile.writeAtomic0600(file, encoded) }.isSuccess) {
                persistedVersion = v
            }
        }
    }

    /** One numeric field, or null when absent or unparseable. A MEMBER taking the object as its
     *  first parameter rather than the extension the original wrote: the receiver is kotlinx's,
     *  so the compliant spelling puts it in the signature (kt-no-extension-functions). */
    private fun long(o: JsonObject, key: String): Long? = (o[key] as? JsonPrimitive)?.content?.toLongOrNull()

    /** An absent or garbage numeric field reads as 0 rather than dropping the whole hour: a
     *  partially-written row should still contribute the counters it does carry. `hour` is the
     *  one exception — without it the row cannot be placed, so [bucketFrom] returns null. */
    private fun longOr(o: JsonObject, key: String): Long = long(o, key) ?: 0L

    private fun bucketFrom(o: JsonObject): EconomicsBucket? {
        val hour = long(o, "hour") ?: return null
        return EconomicsBucket(
            hour = hour,
            turns = longOr(o, "turns"),
            inTokens = longOr(o, "in_tokens"),
            cachedTokens = longOr(o, "cached_tokens"),
            // THE MIGRATION, and it is deliberately the absent-field default rather than a version
            // stamp: every economics.json written before V4-86 carries no `cache_write_tokens` key,
            // and [longOr] reads an absent key as 0 — which is the TRUE historical value, because
            // no cache-write counter existed to sum. A file-format version would have to map the
            // old shape to exactly this, so the version field would carry no information. Pinned by
            // EconomicsStoreTest's old-shape arm, which loads a hand-written 11-key row.
            cacheWriteTokens = longOr(o, "cache_write_tokens"),
            outTokens = longOr(o, "out_tokens"),
            reqBytes = longOr(o, "req_bytes"),
            upstreamBytes = longOr(o, "upstream_req_bytes"),
            toolsEager = longOr(o, "tools_eager"),
            toolsDeferred = longOr(o, "tools_deferred"),
            deferralTurns = longOr(o, "deferral_turns"),
            rateLimited = longOr(o, "rate_limited"),
        )
    }
}
