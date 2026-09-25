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
    // Live InflightGate snapshot (webui HeadPlate "gate inflight / queued"). limit<=0 = unlimited.
    val gateInflight: Int = 0,
    val gateQueued: Int = 0,
    val gateLimit: Int = 0,
    // V4-213: the gate's own counts since the head started, and one reading per slot it holds, all
    // from ONE snapshot taken under the gate's lock. Zero and empty here only for a head with no
    // gate (the test fakes); a running head always reports what its gate measured.
    val gateAcquired: Long = 0,
    val gateReleased: Long = 0,
    val gateWaited: Long = 0,
    /** Mean queue wait of the admissions that waited, rounded; 0 while none has. */
    val gateAvgWaitMs: Long = 0,
    val gateLive: List<GateSlot> = emptyList(),
    /** The head's configured stream-idle limit, the ceiling a live slot's idle is read against. */
    val streamIdleMs: Long = 0,
)

/** Where an admitted turn stands: waiting on the upstream, or hearing from it. */
public enum class GatePhase { CONNECT, STREAMING }

/** One slot the gate holds, read at the snapshot's instant on the monotonic clock. */
public data class GateSlot(
    /** `compact` for a compaction turn, else the model the turn asks upstream for. */
    val label: String,
    val compact: Boolean,
    val phase: GatePhase,
    /** Since admission. */
    val ageMs: Long,
    /** Since the upstream was last heard, or since admission when it has not been yet. */
    val idleMs: Long,
)
