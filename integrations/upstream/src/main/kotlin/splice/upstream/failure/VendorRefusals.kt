// NEW: retry default is total (Marcos's law): a known vendor error gets its own plan, and only an unknown one takes the
// bounded generic retry. A vendor whose own error says the same bytes meet the same answer, such as a plan that does
// not include the model, names it here, in its own module, and the transport stops re-sending it. The phrases stay in
// the vendor module; this file is the neutral question, and the transport's failure site asks it once.
package splice.upstream.failure

/** A refusal a vendor module recognises. [why] is the log line's reason, and [status] is the status the client is
 *  told: the vendor's own, or another when the vendor's would misname the cause (a plan-tier 401 is not a login). */
public data class VendorRefusal(val why: String, val status: Int)

/** Implemented beside a provider's auth: the refusal this vendor's [status] and [body] are, or null for any shape the
 *  vendor module does not know, which keeps every other plan exactly as it was. */
public fun interface VendorRefusals {
    public fun refusal(status: Int, body: String): VendorRefusal?
}
