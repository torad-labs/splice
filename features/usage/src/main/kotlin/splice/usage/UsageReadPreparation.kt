// NEW: (ledger lines 495, 519) the seam by which the app tells Usage readers whether history is prepared.
package splice.usage

/** Optional cold-read preparation; missing preparation keeps the existing synchronous reads. */
public interface UsageReadPreparation {
    public fun economicsReady(head: UsageHead): Boolean

    public fun requestsReady(head: UsageHead, sinceMs: Long): Boolean
}

/** Only management readers opting in receive additive pending replies. Upstream requests never use it. */
public const val USAGE_READ_PENDING_HEADER: String = "x-splice-read-pending"
