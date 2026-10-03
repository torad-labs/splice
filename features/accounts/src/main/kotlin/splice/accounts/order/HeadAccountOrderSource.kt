// NEW: the order policy is a separate capability, not another method on the pool read SAM.
package splice.accounts.order

/** A head's persisted policy and its live candidate order. Labels are the pool's own identities. */
public interface HeadAccountOrderSource {
    public fun order(): List<String>
    public fun effectiveOrder(): List<String>

    /** Unknown or duplicate labels are refused without changing either disk or runtime policy. */
    public fun setOrder(labels: List<String>): Boolean
}
