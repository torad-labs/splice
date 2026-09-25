// NEW: the Head contract (:daemon-control depends on THIS, never on :daemon-head's implementation —
// preserving the separation the old process boundary gave for free; :app wires the map).
package splice.core.head

public interface Head {
    public val key: String
    public val label: String
    public val port: Int

    public suspend fun start()

    public suspend fun stop()

    public suspend fun restart() {
        stop()
        start()
    }

    public fun healthSnapshot(): HeadHealth
}

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
    /** Counts since the head started. */
    val acquired: Long = 0,
    val released: Long = 0,
    val waited: Long = 0,
    /** Mean queue wait of the admissions that waited, rounded; 0 while none has. */
    val avgWaitMs: Long = 0,
    val live: List<GateSlot> = emptyList(),
    /** The head's configured stream-idle limit, the ceiling a live slot's idle is read against. */
    val streamIdleMs: Long = 0,
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
)
