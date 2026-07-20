// NEW: the Head contract (:control depends on THIS, never on :gateway's implementation —
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
)
