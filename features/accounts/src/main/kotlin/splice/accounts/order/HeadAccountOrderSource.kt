// NEW: the order policy is a separate capability, not another method on the pool read SAM.
package splice.accounts.order

/** A head's persisted policy and its live candidate order. Labels are the pool's own identities. */
public interface HeadAccountOrderSource {
    public fun order(): List<String>
    public fun effectiveOrder(): List<String>

    /** The account the next turn uses, then the one the command moves to when that one runs out, in the order the
     *  pool selects by. Null when there is none: a head with one login has nowhere to move. */
    public fun nextTarget(): String? = null
    public fun followingTarget(): String? = null

    /** Unknown or duplicate labels are refused without changing either disk or runtime policy. */
    public fun setOrder(labels: List<String>): Boolean
}
