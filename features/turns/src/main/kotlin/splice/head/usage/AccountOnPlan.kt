// NEW: Oct 11, 2026 (fin) — whether a plan covers a request is a fact about the ACCOUNT that carried it, so the
// rollup asks the head's accounts rather than reading it off the price.
package splice.head.usage

/** Whether a plan covers the requests [account] carries. Read when a turn is recorded, so the hour holds the answer
 *  that was true then. A role of its own: it is NOT any other predicate over an account label. */
public fun interface AccountOnPlan {
    public operator fun invoke(account: String?): Boolean
}

/** A head assembled without a plan lookup records every request as a key's. */
public object NoPlans : AccountOnPlan {
    override fun invoke(account: String?): Boolean = false
}
