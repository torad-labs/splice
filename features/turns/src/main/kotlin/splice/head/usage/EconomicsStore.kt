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
// poll is not an option. This is an O(1)-per-turn accumulator instead: hourly buckets, 35 days
// retained (840 rows, under 300 KB).
//
// SHAPE MIRRORS UsageStore deliberately — same bounded file lane, same coalesced debounce, same
// atomic replace, same best-effort doctrine. A turn must never pay for, nor fail on, telemetry.
//
// V4-221: DOLLARS ARE PRICED PER TURN, AT RECORD TIME, at the card of the model that turn ran. A bucket
// holds a head's hour, and an hour mixes models (a haiku subagent, a compaction model on a head pinned
// to fable), so the console pricing the hour's token sums at the pinned card mispriced every turn on
// another model. Each turn is priced by core's TurnPrice (the budget's arithmetic), summed into
// [EconomicsBucket.costUsd], and a turn with no card counts in [EconomicsBucket.unpricedTurns].
package splice.head.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import splice.core.model.TurnPrice
import splice.core.perf.HISTORY_DEFAULT_DAYS
import splice.core.perf.HistoryWindow
import splice.core.perf.KeptHistory
import splice.core.util.Cancellables
import splice.core.util.CoalescedFlush
import splice.core.util.DaemonLog
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private const val HOUR_MS = 60L * 60 * 1000

// V4-122: the window is splice.core.perf.HistoryWindow now, and the person owns it (Settings > Your
// data, Oct 10, 2026). The console reports the same window in HOURS from :daemon-control/api, which has
// no dependency edge to this module, so the comment that used to claim the two mirrored each other is
// replaced by one declaration both read — and now by one setting the person sets once for both files.

// 840 buckets (35 days) at the ~340 bytes a live head writes is under 300 KB; 4MB is a corrupt-file guard
// with headroom. Measured Oct 10, 2026 on claude-splice: 182 buckets, 61231 bytes.
private const val MAX_FILE_BYTES = 4L * 1024 * 1024
private const val ECONOMICS_FLUSH_DELAY_MS = 1_000L

public class EconomicsStore(
    private val file: Path,
    /** V4-221: the head's pricer. Required: a store without one would record every turn unpriced. */
    private val price: TurnPrice,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
    /** How far back this head's hours are kept — the person's own setting (Settings > Your data),
     *  carried from the records window their install already had, and read at every trim so that
     *  shortening it on the page shortens it now. Defaults to what a fresh install writes, so a
     *  store built without one keeps the shipped window rather than nothing. */
    private val kept: KeptHistory = KeptHistory { HistoryWindow(HISTORY_DEFAULT_DAYS) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fold one finished turn into its hour. Memory-only plus an enqueue — never blocks the turn. */
    public fun record(turn: TurnEconomics) {
        val now = clock()
        val hour = now / HOUR_MS * HOUR_MS
        val localStep = turn.localStep
        val usd = price.usd(turn.model, turn.counters(), now)
        synchronized(lock) {
            loadUnderLock()
            val b = buckets[hour] ?: EconomicsBucket(hour)
            buckets[hour] = b.copy(
                counts = b.counts.record(turn),
                tokens = tokens(b.tokens, turn),
                bytes = BucketBytes(
                    reqBytes = b.reqBytes + (turn.bytes.reqBytes ?: 0),
                    upstreamBytes = b.upstreamBytes + (turn.bytes.upstreamBytes ?: 0),
                ),
                tools = BucketTools(
                    toolsEager = b.toolsEager + (turn.tools.toolsEager ?: 0),
                    toolsDeferred = b.toolsDeferred + (turn.tools.toolsDeferred ?: 0),
                    // Only turns that actually REPORTED a partition count toward the deferral average,
                    // so a head that cannot defer averages over zero turns and reads as "—" rather than
                    // being diluted to a misleading 0.0 by turns that never had the choice.
                    deferralTurns = b.deferralTurns + deferralTurn(turn),
                ),
                rateLimited = b.rateLimited + if (turn.rateLimited) 1 else 0,
                cost = priced(b.cost, usd, localStep),
            )
            trimUnderLock()
            version += 1
        }
        CoalescedFlush.scheduleCoalesced(ECONOMICS_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
    }

    /**
     * Drop every hour that ENDS before [momentMs], and say how many went.
     *
     * This is the save on Settings > Your data, not the window's own trim: the person was shown a
     * count at one moment and said yes to it, so the hours go at that moment rather than at whatever
     * the window works out to later. The same boundary as [trimUnderLock] for the same reason, an
     * hour starts on a UTC hour and a day can begin inside one, and the persist is immediate
     * because a page that reads straight after the yes must not be served the old file.
     */
    public fun trimBefore(momentMs: Long): Int {
        val dropped = synchronized(lock) {
            loadUnderLock()
            dropHoursEndingBefore(momentMs).also { if (it > 0) version += 1 }
        }
        // The promise is about the FILE, not about this call. A read that landed between the config
        // patch and this save has already dropped the expired hours from memory, so this call can
        // drop none of them while every one of them is still on disk: persist whenever memory is
        // ahead of the file, not when this call happened to be the one that moved it.
        if (synchronized(lock) { version > persistedVersion }) flushNow()
        return dropped
    }

    /** Numeric sums are observed values only; the count beside them records unknown turns. */
    private fun tokens(sums: BucketTokens, turn: TurnEconomics): BucketTokens = BucketTokens(
        inTokens = sums.inTokens + (turn.tokens.inTokens ?: 0) + turn.absorbed.inputTokens,
        cachedTokens = sums.cachedTokens + (turn.tokens.cachedTokens ?: 0) + turn.absorbed.cachedTokens,
        cacheWriteTokens = sums.cacheWriteTokens + (turn.tokens.cacheWriteTokens ?: 0) +
            turn.absorbed.cacheWriteTokens,
        outTokens = sums.outTokens + (turn.tokens.outTokens ?: 0),
    )

    private fun deferralTurn(turn: TurnEconomics): Int =
        if (turn.localStep || turn.tools.toolsEager == null) 0 else 1

    /** V4-221: the turn's dollars into its hour; null [usd] is a turn with no card or incomplete usage. A null hour (one
     *  written before the field) stays null: a partial sum must not read as the hour's cost. */
    private fun priced(cost: BucketCost, usd: Double?, localStep: Boolean): BucketCost = BucketCost(
        costUsd = cost.costUsd?.plus(usd ?: 0.0),
        unpricedTurns = cost.unpricedTurns + if (usd == null && !localStep) 1 else 0,
    )

    /** Buckets inside the retention window, oldest first. */
    public fun read(): List<EconomicsBucket> {
        val (ordered, dropped) = synchronized(lock) {
            loadUnderLock()
            val dropped = trimUnderLock()
            buckets.values.sortedBy { it.hour } to dropped
        }
        // What the window dropped has to leave the FILE too, or the next boot reads it straight back
        // in. Scheduled only when an hour actually went, so a page poll that trims nothing — every
        // poll but the first of an hour — writes nothing.
        if (dropped) CoalescedFlush.scheduleCoalesced(ECONOMICS_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
        return ordered
    }

    /**
     * Take hours splice holds the turns for but has no bucket for: the gap between what this file
     * reaches back to and what the request records do (Marlin, Oct 10, 2026 — if splice holds a
     * turn, Usage counts it). Answers the hours it holds afterwards, newest window first.
     *
     * NEVER OVERWRITES AN HOUR IT HAS. An hour recorded as its turns ran is the authority on itself:
     * it was summed from the turns' own usage at the moment each one finished, while a rebuilt hour
     * is read back from rows written on a different path. A bucket already here wins, always, and
     * only the hours missing from the file are added.
     *
     * It is written, not derived per read, because deriving it means parsing every archived
     * generation inside the window on every dashboard poll. Written once, the gap is closed and the
     * next read is the file.
     */
    public fun backfill(hours: List<EconomicsBucket>): List<EconomicsBucket> {
        val (snapshot, v) = synchronized(lock) {
            loadUnderLock()
            hours.forEach { hour -> buckets.putIfAbsent(hour.hour, hour) }
            trimUnderLock()
            val ordered = buckets.values.sortedBy { it.hour }
            buckets.clear()
            ordered.forEach { buckets[it.hour] = it }
            version += 1
            ordered to version
        }
        persist(snapshot, v)
        return snapshot
    }

    /** Force the newest snapshot to stable storage (head stop and deterministic tests). */
    public fun flushNow() {
        val (snapshot, v) = synchronized(lock) { buckets.values.sortedBy { it.hour } to version }
        persist(snapshot, v)
    }

    // ── internals ────────────────────────────────────────────────────────────

    private val lock = Any()
    private val writeLock = Any()
    private val hours = HourReader()
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

    // A window that keeps everything trims nothing: the null cutoff is the whole of `forever`, and
    // the hours stay exactly as they are rather than being compared against an invented horizon.
    //
    // A TRIM IS A CHANGE, wherever it is reached from, so it marks the file stale. A read trims too,
    // because the window may have been shortened since the last write, and a trim that dropped hours
    // without saying so let a GET landing between the config patch and the save do the dropping in
    // memory: the save then found nothing left to drop, skipped its write, and answered a clean
    // "Applied" over a file that still held every expired hour (found in review, Oct 10, 2026).
    // Answers whether anything went, so the caller that reached it can get the file written.
    private fun trimUnderLock(): Boolean {
        val cutoffMs = kept.now().cutoffMs(clock()) ?: return false
        if (dropHoursEndingBefore(cutoffMs) == 0) return false
        version += 1
        return true
    }

    /**
     * Drop every hour that ENDS before [cutoffMs], and say how many went. The one rule both cuts
     * obey: the window's own trim, and the save on Settings > Your data.
     *
     * An hour is dropped only when it ENDS before the cut. A bucket starts on a UTC hour and a
     * window of zero cuts at the operator's own midnight, which in a half-hour zone (Kolkata,
     * UTC+5:30) falls INSIDE an hour: comparing starts would drop the bucket a paid turn just
     * after midnight landed in, and with it the Day figure that turn is budgeted against.
     */
    private fun dropHoursEndingBefore(cutoffMs: Long): Int {
        val going = buckets.keys.filter { it + HOUR_MS <= cutoffMs }
        going.forEach { buckets.remove(it) }
        return going.size
    }

    // best-effort by design: a missing/corrupt file reads as empty; cancellation propagates.
    // V4-151 (DR-58/DR-60 class law): only PROVEN absence — NoSuch with no NOFOLLOW entry — is the
    // quiet first-run empty. The old exists() pre-gate read an inaccessible file as absent, and a
    // corrupt one collapsed to empty with no log at all; either way the next coalesced persist
    // OVERWROTE the hourly history without a trace. The degrade is unchanged; it now says so.
    // Cold path: loadUnderLock runs this once per instance.
    private fun readFromDisk(): List<EconomicsBucket> = Cancellables.runCatchingCancellable {
        val size = Files.size(file)
        if (size > MAX_FILE_BYTES) {
            log("[economics] $file is ${size}B > ${MAX_FILE_BYTES}B cap; history treated as empty\n")
            emptyList()
        } else {
            json.parseToJsonElement(Files.readString(file)).jsonArray
                .mapNotNull { (it as? JsonObject)?.let(hours::bucketFrom) }
        }
    }.getOrElse { failure ->
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (!genuinelyAbsent) {
            log(
                "[economics] $file unreadable/corrupt (${SafeFailureText.render(failure)}); " +
                    "history treated as empty and overwritten at the next persist\n",
            )
        }
        emptyList()
    }

    internal fun flushScheduled() {
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
            val encoded = JsonWire.string(
                buildJsonArray {
                    snapshot.forEach { b ->
                        add(
                            buildJsonObject {
                                put("hour", b.hour)
                                put("turns", b.turns)
                                put("local_steps", b.localSteps)
                                put("unreported_usage_turns", b.counts.unreportedUsageTurns)
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
                                put("cost_usd", b.costUsd) // null writes JSON null: "not priced then"
                                put("unpriced_turns", b.unpricedTurns)
                            },
                        )
                    }
                },
            ) + "\n"
            if (Cancellables.runCatchingCancellable { SecureFile.writeAtomic0600(file, encoded) }.isSuccess) {
                persistedVersion = v
            }
        }
    }

    /** The file's shape, read. Its own class so the store keeps room under detekt's per-class
     *  function ceiling for the behaviour that is the store's, rather than for three field reads. */
    private class HourReader {
        /** One numeric field, or null when absent or unparseable. A MEMBER taking the object as its
         *  first parameter rather than the extension the original wrote: the receiver is kotlinx's,
         *  so the compliant spelling puts it in the signature (kt-no-extension-functions). */
        fun long(o: JsonObject, key: String): Long? = (o[key] as? JsonPrimitive)?.content?.toLongOrNull()

        /** An absent or garbage numeric field reads as 0 rather than dropping the whole hour: a
         *  partially-written row should still contribute the counters it does carry. `hour` is the
         *  one exception: without it the row cannot be placed, so [bucketFrom] returns null. */
        fun longOr(o: JsonObject, key: String): Long = long(o, key) ?: 0L

        fun bucketFrom(o: JsonObject): EconomicsBucket? {
            val hour = long(o, "hour") ?: return null
            return EconomicsBucket(
                hour = hour,
                counts = EconomicsTurnCounts(
                    longOr(o, "turns"),
                    longOr(o, "local_steps"),
                    longOr(o, "unreported_usage_turns"),
                ),
                tokens = BucketTokens(
                    inTokens = longOr(o, "in_tokens"),
                    cachedTokens = longOr(o, "cached_tokens"),
                    // THE MIGRATION, and it is deliberately the absent-field default rather than a version
                    // stamp: every economics.json written before V4-86 carries no `cache_write_tokens` key,
                    // and [longOr] reads an absent key as 0, which is the TRUE historical value, because
                    // no cache-write counter existed to sum. A file-format version would have to map the
                    // old shape to exactly this, so the version field would carry no information. Pinned by
                    // EconomicsStoreTest's old-shape arm, which loads a hand-written 11-key row.
                    cacheWriteTokens = longOr(o, "cache_write_tokens"),
                    outTokens = longOr(o, "out_tokens"),
                ),
                bytes = BucketBytes(
                    reqBytes = longOr(o, "req_bytes"),
                    upstreamBytes = longOr(o, "upstream_req_bytes"),
                ),
                tools = BucketTools(
                    toolsEager = longOr(o, "tools_eager"),
                    toolsDeferred = longOr(o, "tools_deferred"),
                    deferralTurns = longOr(o, "deferral_turns"),
                ),
                rateLimited = longOr(o, "rate_limited"),
                cost = BucketCost(
                    // V4-221: an hour written before the field has no `cost_usd` key and reads NULL, not
                    // priced then, which is the true historical value; JSON null (an hour that stayed
                    // unpriceable) reads null too.
                    costUsd = (o["cost_usd"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                    unpricedTurns = longOr(o, "unpriced_turns"),
                ),
            )
        }
    }
}
