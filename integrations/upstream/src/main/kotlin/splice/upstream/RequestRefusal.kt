// NEW: failure-handling slice 1 (2026-10-26). A request a provider refuses before any turn is built, as its sealed
// cause and the sentence the client reads. The cause decides the answer (ClientWire, at admission); the sentence stays
// the refusing site's own until slice 2 gives every line one author.
package splice.upstream

import splice.core.failure.FailureCause

/** A caller constraint this provider cannot honour: why it was refused ([cause]) and what the client reads
 *  ([message]). */
public data class RequestRefusal(val cause: FailureCause.Splice.Declined, val message: String)
