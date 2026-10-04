// PORT-OF: UsageHud.kt @ d8653a0 — invariants unchanged: public constructor and every public
// method are byte-identical (HD-24, 2026-08-17). Now a facade wiring UsageRingFile -> UsageRing ->
// RateLimitFile/RateLimitHeaders -> RateLimitStore, so Daemon (3 sites), HeadServer (2),
// TurnDriver (3), FileSources (2) and every construction site across gateway/provider-* tests see
// no change.
package splice.head.usage

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import splice.core.head.ProviderAnswer
import splice.core.usage.QuotaHeaderRead
import splice.core.usage.RateLimitState
import splice.core.util.Cancellables
import splice.core.util.CoalescedFlush
import splice.core.util.DaemonLog
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Widened from private to internal (HD-24): UsageRing (a sibling file) needs the same window.
internal const val FIVE_HOURS_MS: Long = 5 * 60 * 60 * 1000

// Widened from private to internal (HD-24): RateLimitStore (a sibling file) schedules on the
// same 1s lane.
internal const val USAGE_FLUSH_DELAY_MS: Long = 1_000L

/** 5h output-token window + ratelimit header persistence — the HUD contract files. */
public class UsageStore(
    usageFile: Path,
    ratelimitFile: Path,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    log: LogSink = LogSink(DaemonLog::write),
) {
    // Shared by flushRateLimit and persistSnapshot (review 2026-07-22): one Any() serializes both
    // lanes' disk writes across UsageRingFile and RateLimitStore. Splitting it into two locks
    // would be a logic change (out of scope here).
    private val writeLock = Any()
    private val ringFile = UsageRingFile(usageFile, writeLock, log)
    private val ring = UsageRing(ringFile, clock() - FIVE_HOURS_MS)
    private val rateLimitStore = RateLimitStore(RateLimitFile(ratelimitFile), RateLimitHeaders(clock), writeLock)
    private val writeScheduled = AtomicBoolean(false)
    private val answers = ProviderAnswers(
        RateLimitFile(ratelimitFile.resolveSibling("${ratelimitFile.fileName}.provider-answer"), log),
        log = log,
    )

    /** Header arrival is immediate in memory; the existing bounded file lane coalesces its persistence. */
    public fun observeProviderAnswer(status: Int, observedAtEpochMs: Long): Unit =
        answers.record(ProviderAnswer(status, observedAtEpochMs))

    /** A real WebSocket acceptance/refusal carries no HTTP status; never manufacture one. */
    public fun observeProviderStreamAnswer(accepted: Boolean, observedAtEpochMs: Long): Unit =
        answers.record(ProviderAnswer(null, observedAtEpochMs, accepted))

    public fun providerAnswer(): ProviderAnswer? = answers.snapshot()

    // In-memory updates are immediate. Persistence is coalesced onto the bounded file-I/O lane,
    // minute-bucketed, serialized, and atomically replaced: completion bursts neither block turn
    // slots nor race older snapshots over newer ones.
    public fun appendOutputTokens(outputTokens: Long) {
        if (ring.appendOutputTokens(clock(), outputTokens)) {
            CoalescedFlush.scheduleCoalesced(USAGE_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
        }
    }

    public fun persistRateLimit(header: QuotaHeaderRead): Unit = rateLimitStore.persistRateLimit(header)

    public fun readState(): UsageState {
        val (entries, tokens) = ring.stats(clock() - FIVE_HOURS_MS)
        return UsageState(
            windowHours = 5,
            entries = entries,
            outputTokens5h = tokens,
            ratelimit = rateLimitStore.readRateLimit(),
        )
    }

    /** Force the newest in-memory snapshot to stable storage (head stop and deterministic tests). */
    public fun flushNow() {
        val (snapshot, version) = ring.trimmedSnapshot(clock() - FIVE_HOURS_MS)
        ringFile.persistSnapshot(snapshot, version)
        rateLimitStore.flushRateLimit()
        answers.flush()
    }

    public fun readRateLimit(): RateLimitState? = rateLimitStore.readRateLimit()

    private fun flushScheduled() {
        val (snapshot, version) = ring.snapshot()
        ringFile.persistSnapshot(snapshot, version)
        writeScheduled.set(false)
        if (ring.isDirtierThan(ringFile.persistedVersion)) {
            CoalescedFlush.scheduleCoalesced(USAGE_FLUSH_DELAY_MS, writeScheduled) { flushScheduled() }
        }
    }
}

/** The write seam lets a burst test count persistence, not merely scheduled callbacks. */
internal fun interface ProviderAnswerSink {
    fun write(answer: ProviderAnswer)
}

/** A retained head-wide observation, independent of quota percentages and credential verdicts. */
internal class ProviderAnswers(
    private val file: RateLimitFile,
    private val sink: ProviderAnswerSink = ProviderAnswerSink { file.write(ProviderAnswerJson.encode(it)) },
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val latest = AtomicReference(read())
    private val dirty = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val writeLock = Any()
    private var failureLogged = false

    fun snapshot(): ProviderAnswer? = latest.get()

    fun record(answer: ProviderAnswer) {
        latest.accumulateAndGet(answer) { current, _ ->
            if (current == null || answer.observedAtEpochMs >= current.observedAtEpochMs) answer else current
        }
        dirty.set(true)
        CoalescedFlush.scheduleCoalesced(USAGE_FLUSH_DELAY_MS, scheduled) { flush() }
    }

    fun flush() {
        synchronized(writeLock) {
            try {
                if (!dirty.getAndSet(false)) return
                latest.get()?.let(::persist)
            } finally {
                scheduled.set(false)
                if (dirty.get() && !failureLogged) {
                    CoalescedFlush.scheduleCoalesced(USAGE_FLUSH_DELAY_MS, scheduled) { flush() }
                }
            }
        }
    }

    private fun persist(answer: ProviderAnswer) {
        Cancellables.runCatchingCancellable { sink.write(answer) }.fold(
            onSuccess = { failureLogged = false },
            onFailure = {
                dirty.set(true)
                if (!failureLogged) {
                    log("[provider answer] flush FAILED (${SafeFailureText.render(it)}); latest retained in memory\n")
                    failureLogged = true
                }
            },
        )
    }

    private fun read(): ProviderAnswer? = file.read()?.let { raw ->
        val status = (raw["status"] as? JsonPrimitive)?.intOrNull
        val at = (raw["observed_at_epoch_ms"] as? JsonPrimitive)?.longOrNull ?: return@let invalidSnapshot()
        val accepted = (raw["accepted"] as? JsonPrimitive)?.booleanOrNull ?: return@let invalidSnapshot()
        val validStatus = if (status == null) {
            raw["status"] == kotlinx.serialization.json.JsonNull
        } else {
            status in HttpStatus.MIN_CODE..HttpStatus.MAX_CODE && accepted == ProviderAnswer(status, at).accepted
        }
        if (validStatus && at > 0L) ProviderAnswer(status, at, accepted) else invalidSnapshot()
    }

    private fun invalidSnapshot(): ProviderAnswer? {
        log("[provider answer] invalid snapshot; no observation until the next provider answer\n")
        return null
    }
}

private object ProviderAnswerJson {
    fun encode(answer: ProviderAnswer): String = JsonWire.string(
        buildJsonObject {
            put("status", answer.status)
            put("observed_at_epoch_ms", answer.observedAtEpochMs)
            put("accepted", answer.accepted)
        },
    )
}
