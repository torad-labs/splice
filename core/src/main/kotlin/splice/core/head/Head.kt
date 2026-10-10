// NEW: the Head contract (:daemon-control depends on THIS, never on :daemon-head's implementation —
// preserving the separation the old process boundary gave for free; :app wires the map).
package splice.core.head

import splice.core.usage.QuotaFull
import splice.core.wire.HttpStatus

public interface Head {
    public val key: String
    public val label: String
    public val port: Int

    public suspend fun start()

    /** Stop taking new turns and let the running ones finish, WITH THE PORT STILL LISTENING. A daemon stopping
     *  several heads drains them all before any listener closes, so a head that drains early keeps answering
     *  its clients instead of refusing their connections while its siblings finish. [stop] does both halves, so
     *  a head with nothing to drain needs nothing here. */
    public suspend fun drain() {}

    public suspend fun stop()

    public suspend fun restart() {
        stop()
        start()
    }

    public fun healthSnapshot(): HeadHealth

    /** Distinct ended-turn rate refusals since restart, absent on an uninstrumented head. */
    public fun rateLimitSnapshot(): RateLimitHealth? = null

    /** Last actual provider acceptance or refusal, retained across restarts; absence proves no acceptance. */
    public fun providerAnswer(): ProviderAnswer? = null

    /** Newest answer of the current credential owner, never another account's head-wide answer. */
    public fun providerAnswer(account: String?): ProviderAnswer? = null

    /** Milliseconds left on a refusal this head is HOLDING (V4-398/V4-412), zero when it holds none. The one value
     *  any surface may print as out of quota (V4-452). */
    public fun providerResetForMs(): Long = 0L

    /** V4-452: the window the provider's current reading names fully used, null when none is. A reading, not a
     *  refusal: the head stays ready beside it. */
    public fun quotaFull(): QuotaFull? = null
}

/** HTTP headers or a WebSocket response frame, never body completion or a local failure. No identifiers. */
public data class ProviderAnswer(
    /** Original HTTP status; null when a WebSocket event carries no HTTP status. */
    val status: Int?,
    val observedAtEpochMs: Long,
    val accepted: Boolean = status != null && status in HttpStatus.OK..HttpStatus.MAX_SUCCESS,
    /** A 429 counts as a login refusal only when the provider names a spent subscription window. */
    val quotaRefused: Boolean = false,
) {
    public val refused: Boolean get() = status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN ||
        (status == HttpStatus.TOO_MANY_REQUESTS && quotaRefused)
}

/** Provider rate failures and local cooldown holds are disjoint, not absorbed-round error events. */
public data class RateLimitHealth(val providerTurns: Long, val heldTurns: Long)

public data class HeadHealth(
    val ok: Boolean,
    val running: Boolean,
    val port: Int,
    val version: String,
    // G20: cheap in-memory passive health counters, local-origin vs provider-error (Envoy
    // split_external_local_origin_errors shape). Reset on head restart — diagnosis, not telemetry.
    val localOriginErrors: Long = 0,
    val providerErrors: Long = 0,
    val gate: GateHealth = GateHealth(),
)

/** The head's InflightGate as the console reads it (webui HeadPlate, the in-flight list). V4-213:
 *  every field but [streamIdleMs] comes from ONE snapshot taken under the gate's lock. Zero and
 *  empty only for a head with no gate (the test fakes); a running head reports what its gate
 *  measured. One bundle, so the gate's reading is one value on [HeadHealth], not nine columns. */
public data class GateHealth(
    val inflight: Int = 0,
    val queued: Int = 0,
    /** limit<=0 = unlimited. */
    val limit: Int = 0,
    val counts: GateCounts = GateCounts(),
    val live: List<GateSlot> = emptyList(),
    /** The head's configured stream-idle limit, the ceiling a live slot's idle is read against. */
    val streamIdleMs: Long = 0,
)

/** What the gate has admitted and held since the head started. */
public data class GateCounts(
    val acquired: Long = 0,
    val released: Long = 0,
    val waited: Long = 0,
    /** Mean queue wait of the admissions that waited, rounded; 0 while none has. */
    val avgWaitMs: Long = 0,
)

/** Where an admitted turn stands: waiting on the upstream, or hearing from it. */
public enum class GatePhase { CONNECT, STREAMING }

/** One slot the gate holds, read at the snapshot's instant on the monotonic clock. */
public data class GateSlot(
    /** The session's short tag then the model the turn asks upstream for; the model alone when the
     *  client sent no session. */
    val label: String,
    val compact: Boolean,
    val phase: GatePhase,
    /** Since admission. */
    val ageMs: Long,
    /** Since the upstream was last heard, or since admission when it has not been yet. */
    val idleMs: Long,
    /** The opaque live-turn stop id, absent when this slot has no listed streaming turn. */
    val turnId: String? = null,
)
