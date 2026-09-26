// NEW: V4-244 — each client session's running total, kept as its perf rows are appended, so the
// status line prices a session of any length without reading the log.
//
// WHY NOT THE TAIL. PerfStats reads the last 256 KiB of the head's perf log. On the real claudex log
// that is 469 rows, and 30 of its 59 sessions ran longer (one ran 252 hours and 23548 rows), so their
// figure was a lower bound (V4-240 review, finding 4c). Re-reading 64 MB per status-line tick is not
// an option. This is EconomicsStore's O(1)-per-turn accumulator keyed by session instead of by hour:
// the same coalesced atomic replace, the same best-effort doctrine (a turn never pays for, nor fails
// on, telemetry).
//
// FED BY THE APPEND. PerfStats.record hands this store each row it appends, so a total is the sum of
// its session's rows by construction. Each turn is priced then, by core's TurnPrice, at the card of
// the model it ran on (V4-240 review, 4b), and never as part of a sum, because a long-context tier
// bills one request by its own size. A turn with no card is kept unpriced; a turn that spent nothing
// (a local refusal) is not a turn to price, the rule SessionCost's tail path follows.
//
// WHAT A TOTAL MAY CLAIM. [since] is when this store began counting: every row appended since then is
// in a total. A total is born carrying it as [PerfSessionTotal.fromMs], and a session that began
// earlier may have rows no total counted, so its figure stays a lower bound. Dropping a total (idle
// past SESSION_IDLE_RETENTION_MS, or past MAX_SESSION_TOTALS) moves [since] past that total's newest
// row, so the same tag coming back is born after its lost rows and cannot claim them; a live total
// keeps the start it was born with. The file says whether a head stop wrote it last ("clean"):
// anything else (a kill, a crash, a failed final write) may have lost rows the file never saw, so the
// totals start over at this process's start and claim nothing older, as they do for a corrupt file.
package splice.head.perf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.perf.PerfModelTotal
import splice.core.perf.PerfSessionTotal
import splice.core.util.Cancellables
import splice.core.util.CoalescedFlush
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** A session idle this long loses its total: longer than the longest session measured (252 hours on
 *  the claudex log, 2026-09-25), so a live session never does. */
internal const val SESSION_IDLE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

/** The most totals one head keeps, least recently active dropped first: about three months of the
 *  claudex head's sessions, and a file of tens of KiB. */
internal const val MAX_SESSION_TOTALS = 256

// A corrupt-file guard with headroom: MAX_SESSION_TOTALS totals of a few models each are well under it.
private const val TOTALS_MAX_FILE_BYTES = 4L * 1024 * 1024

// why: EconomicsStore's debounce, so a burst of turns costs one write a second, not one write a turn.
private const val TOTALS_FLUSH_DELAY_MS = 1_000L

/** The perf-row counters a turn spends through; a turn with none of them spent nothing. */
private val TOKEN_KEYS = listOf(
    PerfKeys.IN_TOKENS,
    PerfKeys.CACHED_TOKENS,
    PerfKeys.CACHE_WRITE_TOKENS,
    PerfKeys.OUT_TOKENS,
)

/** One session's total as the store keeps it, with the ts of its newest row. */
private data class Kept(val total: PerfSessionTotal, val lastMs: Long)

/** What the file held: whether a head stop wrote it last, when its counting began, each tag's total. */
private data class Loaded(val clean: Boolean, val since: Long, val sessions: List<Pair<String, Kept>>)

public class SessionTotals(
    private val file: Path,
    /** The head's pricer, the one its EconomicsStore prices the hour with. */
    private val price: TurnPrice,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val bornAt = clock()
    private val lock = Any()
    private val writeLock = Any()

    // Guarded by [lock]. Insertion order is recency: a tag is re-inserted on each of its rows.
    private val sessions = LinkedHashMap<String, Kept>()
    private var since = 0L
    private var loaded = false
    private var version = 0L

    /** Set by [flushNow] (a head stop): the file is clean, and the next [add] must say it is not. */
    private var closed = false

    @Volatile
    private var persistedVersion = -1L
    private val writeScheduled = AtomicBoolean(false)

    /** Fold one appended row into its session's total. Memory plus an enqueue, so it never blocks
     *  the turn; the first row after a head stop writes the file through, marked not clean. */
    public fun add(sessionTag: String, model: String, counters: Map<String, Long>, rowTs: Long) {
        if (sessionTag.isEmpty() || TOKEN_KEYS.all { (counters[it] ?: 0L) == 0L }) return
        val usd = price.usd(model, counters)
        val reopened = synchronized(lock) {
            loadUnderLock()
            dropIdleUnderLock()
            val kept = sessions.remove(sessionTag) ?: Kept(PerfSessionTotal(since, emptyMap()), rowTs)
            val models = kept.total.models + (model to folded(kept.total.models[model], counters, usd))
            sessions[sessionTag] = Kept(kept.total.copy(models = models), maxOf(kept.lastMs, rowTs))
            while (sessions.size > MAX_SESSION_TOTALS) dropUnderLock(sessions.keys.first())
            version += 1
            closed.also { closed = false }
        }
        if (reopened) persist(clean = false) else schedule()
    }

    /** [sessionId]'s total, matched the way PerfStats matches a row: the stored tag is a truncation of
     *  the id the caller holds. Null for a session with no counted row, and for an empty id. */
    public fun totalFor(sessionId: String): PerfSessionTotal? {
        if (sessionId.isEmpty()) return null
        return synchronized(lock) {
            loadUnderLock()
            sessions.entries.firstOrNull { (tag, _) -> tag.isNotEmpty() && sessionId.startsWith(tag) }?.value?.total
        }
    }

    /** The totals to stable storage, marked clean: called at head stop, after its turns drained. */
    public fun flushNow() {
        synchronized(lock) { closed = true }
        persist(clean = true)
    }

    private fun folded(prev: PerfModelTotal?, counters: Map<String, Long>, usd: Double?): PerfModelTotal {
        val p = prev ?: PerfModelTotal(0, 0, 0, 0, 0, usd = 0.0, unpricedTurns = 0)
        return PerfModelTotal(
            turns = p.turns + 1,
            inTokens = p.inTokens + (counters[PerfKeys.IN_TOKENS] ?: 0L),
            cachedTokens = p.cachedTokens + (counters[PerfKeys.CACHED_TOKENS] ?: 0L),
            cacheWriteTokens = p.cacheWriteTokens + (counters[PerfKeys.CACHE_WRITE_TOKENS] ?: 0L),
            outTokens = p.outTokens + (counters[PerfKeys.OUT_TOKENS] ?: 0L),
            usd = p.usd + (usd ?: 0.0),
            unpricedTurns = p.unpricedTurns + if (usd == null) 1 else 0,
        )
    }

    private fun dropIdleUnderLock() {
        val cutoff = clock() - SESSION_IDLE_RETENTION_MS
        sessions.filterValues { it.lastMs < cutoff }.keys.forEach(::dropUnderLock)
    }

    /** Drop [tag]'s total, and move [since] past its newest row so no later total claims its rows. */
    private fun dropUnderLock(tag: String) {
        val kept = sessions.remove(tag) ?: return
        since = maxOf(since, kept.lastMs + 1)
    }

    private fun loadUnderLock() {
        if (loaded) return
        loaded = true
        val disk = readFromDisk()
        since = disk?.since ?: bornAt
        disk?.sessions?.sortedBy { (_, kept) -> kept.lastMs }?.forEach { (tag, kept) -> sessions[tag] = kept }
    }

    // DR-60 (class law): only PROVEN absence is the quiet first run. A file past the size guard, one
    // that will not read, and one no head stop closed all start over the same way and say so, because
    // the totals they held may be short of rows the file never saw. Cold path: runs once.
    private fun readFromDisk(): Loaded? {
        val disk = Cancellables.runCatchingCancellable {
            val size = Files.size(file)
            if (size > TOTALS_MAX_FILE_BYTES) {
                startOver("is ${size}B, past the ${TOTALS_MAX_FILE_BYTES}B guard")
                null
            } else {
                TotalsFile.decode(json.parseToJsonElement(Files.readString(file)).jsonObject)
            }
        }.getOrElse { failure ->
            val genuinelyAbsent = failure is NoSuchFileException && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            if (!genuinelyAbsent) startOver("did not read (${SafeFailureText.render(failure)})")
            null
        }
        if (disk?.clean == false) startOver("was not closed by a head stop (a kill or a crash)")
        return disk?.takeIf { it.clean }
    }

    private fun startOver(why: String) {
        log("[session-totals] $file $why; totals start over, and a session begun before now reads as a lower bound\n")
    }

    private fun schedule() {
        CoalescedFlush.scheduleCoalesced(TOTALS_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
    }

    private fun flushScheduled() {
        persist(clean = false)
        writeScheduled.set(false)
        if (synchronized(lock) { version > persistedVersion }) schedule()
    }

    private fun persist(clean: Boolean) {
        val (encoded, v) = synchronized(lock) { TotalsFile.encode(clean, since, sessions) to version }
        synchronized(writeLock) {
            // A clean write goes out even at a version already written, because its mark is the news.
            if (v <= persistedVersion && !clean) return
            if (Cancellables.runCatchingCancellable { SecureFile.writeAtomic0600(file, encoded) }.isSuccess) {
                persistedVersion = maxOf(persistedVersion, v)
            }
        }
    }
}

/** The totals file's format, apart from the store's state and locking. Strict on purpose: a field that
 *  will not read fails the whole file, which then starts over. Dropping one session instead would let
 *  its tag come back with a start that claims its lost rows. Every failure is an
 *  IllegalArgumentException (require, requireNotNull, kotlinx's jsonObject and jsonPrimitive), the
 *  type the store's read catches. */
private object TotalsFile {
    fun decode(root: JsonObject): Loaded = Loaded(
        clean = field(root, "clean").jsonPrimitive.booleanOrNull == true,
        since = long(root, "since"),
        sessions = field(root, "sessions").jsonArray.map { kept(it.jsonObject) },
    )

    fun encode(clean: Boolean, since: Long, sessions: Map<String, Kept>): String = buildJsonObject {
        put("clean", clean)
        put("since", since)
        put(
            "sessions",
            buildJsonArray {
                sessions.forEach { (tag, kept) ->
                    add(
                        buildJsonObject {
                            put("session", tag)
                            put("from", kept.total.fromMs)
                            put("last", kept.lastMs)
                            putJsonObject("models") {
                                kept.total.models.forEach { (model, t) ->
                                    putJsonObject(model) {
                                        put("turns", t.turns)
                                        put("in_tokens", t.inTokens)
                                        put("cached_tokens", t.cachedTokens)
                                        put("cache_write_tokens", t.cacheWriteTokens)
                                        put("out_tokens", t.outTokens)
                                        put("cost_usd", t.usd)
                                        put("unpriced_turns", t.unpricedTurns)
                                    }
                                }
                            }
                        },
                    )
                }
            },
        )
    }.toString() + "\n"

    private fun kept(o: JsonObject): Pair<String, Kept> {
        val models = field(o, "models").jsonObject.mapValues { (_, m) ->
            val t = m.jsonObject
            PerfModelTotal(
                turns = long(t, "turns"),
                inTokens = long(t, "in_tokens"),
                cachedTokens = long(t, "cached_tokens"),
                cacheWriteTokens = long(t, "cache_write_tokens"),
                outTokens = long(t, "out_tokens"),
                usd = field(t, "cost_usd").jsonPrimitive.double,
                unpricedTurns = long(t, "unpriced_turns"),
            )
        }
        val tag = field(o, "session").jsonPrimitive.content
        return tag to Kept(PerfSessionTotal(long(o, "from"), models), long(o, "last"))
    }

    private fun long(o: JsonObject, key: String): Long = field(o, key).jsonPrimitive.long

    private fun field(o: JsonObject, key: String): JsonElement = requireNotNull(o[key]) { "no \"$key\"" }
}
