// PORT-OF: UsageHud.kt @ d8653a0 — invariants unchanged: public constructor and every public
// method are byte-identical (HD-24, 2026-08-17). Now a facade wiring UsageRingFile -> UsageRing ->
// RateLimitFile/RateLimitHeaders -> RateLimitStore, so Daemon (3 sites), HeadServer (2),
// TurnDriver (3), FileSources (2) and every construction site across gateway/provider-* tests see
// no change.
package splice.head.usage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.auth.AuthProvider
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
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Widened from private to internal (HD-24): UsageRing (a sibling file) needs the same window.
internal const val FIVE_HOURS_MS: Long = 5 * 60 * 60 * 1000

// Widened from private to internal (HD-24): RateLimitStore (a sibling file) schedules on the
// same 1s lane.
internal const val USAGE_FLUSH_DELAY_MS: Long = 1_000L

/** The usage files' write monitor: the ring and the rate-limit file serialize their disk writes under it. */
internal class UsageWriteLock

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
    private val writeLock = UsageWriteLock()
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

    /** Retains the WebSocket answer under the credential that actually carried that round. */
    public fun observeProviderStreamAnswer(
        auth: AuthProvider,
        accepted: Boolean,
        at: Long,
        credentialKey: String?,
    ): Unit = answers.record(ProviderAnswer(null, at, accepted), auth, credentialKey)

    public fun providerAnswer(): ProviderAnswer? = answers.snapshot()

    /** A retained head observation with no credential attribution; never a current login verdict. */
    public fun unscopedProviderAnswer(): ProviderAnswer? = answers.unscopedSnapshot()

    /** The captured credential owner is never read, refreshed or serialized by observation. */
    public fun observeProviderAnswer(
        auth: AuthProvider,
        status: Int,
        at: Long,
        quotaRefused: Boolean = false,
        credentialKey: String? = answers.key(auth),
    ): Unit = answers.record(ProviderAnswer(status, at, quotaRefused = quotaRefused), auth, credentialKey)

    public fun providerAnswer(auth: AuthProvider): ProviderAnswer? = answers.snapshot(auth)

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
    private val sink: ProviderAnswerSink? = null,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val restored = file.read()
    private val latest = AtomicReference(read(restored))
    private val byCredential = ConcurrentHashMap(ProviderAnswerJson.credentials(restored))

    // Owners without file evidence are process-local; retired owners are not kept alive with their credentials.
    private val credentials = WeakHashMap<AuthProvider, ProviderAnswer>()
    private val dirty = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val writeLock = Any()
    private var failureLogged = false

    fun snapshot(): ProviderAnswer? = latest.get()

    fun unscopedSnapshot(): ProviderAnswer? = latest.get().takeIf { byCredential.isEmpty() }

    fun key(auth: AuthProvider): String? = auth.observedCredentialKey()

    fun snapshot(auth: AuthProvider): ProviderAnswer? {
        val key = key(auth)
        return if (key != null) byCredential[key] else synchronized(credentials) { credentials[auth] }
    }

    fun record(answer: ProviderAnswer, auth: AuthProvider? = null, key: String? = auth?.let(::key)) {
        if (key != null) byCredential.compute(key) { _, current -> newest(current, answer) }
        if (auth != null) {
            synchronized(credentials) { credentials[auth] = newest(credentials[auth], answer) }
        }
        latest.accumulateAndGet(answer) { current, _ -> newest(current, answer) }
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

    private fun newest(current: ProviderAnswer?, answer: ProviderAnswer): ProviderAnswer =
        if (current == null || answer.observedAtEpochMs >= current.observedAtEpochMs) answer else current

    private fun persist(answer: ProviderAnswer) {
        Cancellables.runCatchingCancellable {
            sink?.write(answer) ?: file.write(ProviderAnswerJson.encode(answer, byCredential.toMap()))
        }.fold(
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

    private fun read(raw: JsonObject?): ProviderAnswer? =
        raw?.let { ProviderAnswerJson.decode(it) ?: invalidSnapshot() }

    private fun invalidSnapshot(): ProviderAnswer? {
        log("[provider answer] invalid snapshot; no observation until the next provider answer\n")
        return null
    }
}

private object ProviderAnswerJson {
    private val key = Regex("[0-9a-f]{64}")

    fun encode(answer: ProviderAnswer, credentials: Map<String, ProviderAnswer>): String = JsonWire.string(
        buildJsonObject {
            fields(this, answer)
            putJsonObject("credentials") {
                credentials.forEach { (key, observed) -> putJsonObject(key) { fields(this, observed) } }
            }
        },
    )

    fun credentials(raw: JsonObject?): Map<String, ProviderAnswer> =
        (raw?.get("credentials") as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            if (!this.key.matches(key)) return@mapNotNull null
            (value as? JsonObject)?.let(::decode)?.let { key to it }
        }.toMap()

    fun decode(raw: JsonObject): ProviderAnswer? {
        val status = (raw["status"] as? JsonPrimitive)?.intOrNull
        val at = (raw["observed_at_epoch_ms"] as? JsonPrimitive)?.longOrNull ?: return null
        val accepted = (raw["accepted"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val validStatus = if (status == null) {
            raw["status"] == kotlinx.serialization.json.JsonNull
        } else {
            status in HttpStatus.MIN_CODE..HttpStatus.MAX_CODE && accepted == ProviderAnswer(status, at).accepted
        }
        val quotaRefused = (raw["quota_refused"] as? JsonPrimitive)?.booleanOrNull == true
        return if (validStatus && at > 0L) ProviderAnswer(status, at, accepted, quotaRefused) else null
    }

    private fun fields(into: JsonObjectBuilder, answer: ProviderAnswer) = with(into) {
        put("status", answer.status)
        put("observed_at_epoch_ms", answer.observedAtEpochMs)
        put("accepted", answer.accepted)
        put("quota_refused", answer.quotaRefused)
    }
}
