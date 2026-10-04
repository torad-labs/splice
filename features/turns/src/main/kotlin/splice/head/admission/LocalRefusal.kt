// NEW: V4-174 — a turn refused by the head itself, before any upstream call (the armed rate-limit
// cooldown, every pooled account exhausted): the perf row's tag, the log line's detail, and the
// turn's trace when the head's is on, so the refusal is on record beside the turns that ran.
// One value instead of three more parameters on recordLocalRefusal (detekt's LongParameterList).
package splice.head.admission

import splice.head.wire.TurnTrace

internal data class LocalRefusal(val tag: String, val detail: String, val trace: TurnTrace?)
