// NEW: the Head contract (:control depends on THIS, never on :gateway's implementation —
// preserving the separation the old process boundary gave for free; :app wires the map).
package splice.core.head

public interface Head {
    public val key: String
    public val label: String
    public val port: Int

    public suspend fun start()

    public suspend fun stop()

    public fun healthSnapshot(): HeadHealth
}

public data class HeadHealth(
    val ok: Boolean,
    val running: Boolean,
    val port: Int,
    val version: String,
)
