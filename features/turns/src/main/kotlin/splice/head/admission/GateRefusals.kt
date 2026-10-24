// NEW: V4-444 — splice's own refusals at the front door are requests the operator should see.
//
// A request turned away because the head is stopping, or because the gate is full, used to leave only a log line: the
// client was told, nobody else was. This seam hands the refusal to whatever records rows, so the console's Requests
// list shows what splice itself declined beside what it served.
package splice.head.admission

import splice.core.perf.OutcomeTag

/** Records one request splice turned away at the gate, under [tag], for the client session [session] when it named one.
 *  The default wired in tests records nothing. */
internal fun interface GateRefusals {
    fun refused(tag: OutcomeTag, session: String?)
}
