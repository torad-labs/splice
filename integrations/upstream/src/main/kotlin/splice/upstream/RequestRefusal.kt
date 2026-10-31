// NEW: failure-handling slice 1 (2026-10-26). A request a provider refuses before any turn is built, as its sealed
// cause. The cause decides the answer (ClientWire, at admission), and since slice 2 the line a person reads is
// SpliceLines' own, written from the cause, so the refusing site words nothing.
package splice.upstream

import splice.core.failure.FailureCause

/** A caller constraint this provider cannot honour, as why it was refused ([cause]). */
public data class RequestRefusal(val cause: FailureCause.Splice.Declined)
