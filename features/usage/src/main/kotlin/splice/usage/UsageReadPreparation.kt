// NEW: (ledger lines 495, 519) the seam by which the app tells Usage readers whether history is prepared.
package splice.usage

/** Optional cold-read preparation; missing preparation keeps the existing synchronous reads. */
public interface UsageReadPreparation {
    public fun economicsReady(head: UsageHead): Boolean

    public fun requestsReady(head: UsageHead, sinceMs: Long): Boolean

    /** The keys of [heads] whose request window from [sinceMs] is still being read, each asked once. */
    public fun requestsPending(heads: List<UsageHead>, sinceMs: Long): Set<String> =
        heads.filterNot { requestsReady(it, sinceMs) }.mapTo(HashSet()) { it.key }
}

/** Only management readers opting in receive additive pending replies. Upstream requests never use it. */
public const val USAGE_READ_PENDING_HEADER: String = "x-splice-read-pending"
