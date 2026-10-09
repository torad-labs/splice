// NEW: the two wall-clock caps the translator names in its failure messages, shared by the turn
// context and the seams' construction bag so neither carries two bare Longs of the same unit.
package splice.dialect.responses

/** The watchdog's stream-idle cap and total upstream cap, in milliseconds, as the failure text states them. */
internal data class WatchdogCaps(
    val streamIdleMs: Long,
    val upstreamTimeoutMs: Long,
)
