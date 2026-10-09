package splice.upstream.retry

/** What an [InflightGate] has delivered since it was built: slots acquired and released, the deliveries that
 *  waited in the queue first, and their mean wait in milliseconds, rounded (0 while none has). */
public data class GateTraffic(
    val acquired: Long,
    val released: Long,
    val waited: Long,
    val avgWaitMs: Long,
)
