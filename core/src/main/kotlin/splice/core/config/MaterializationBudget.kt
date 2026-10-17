// NEW: Oct 10, 2026 — the two readers of the materialization budget, in ONE file because the trap is BETWEEN them:
// what the operator configured (0 means derive) and the ceiling an enforcer compares a body against (always > 0).
package splice.core.config

/** The heap bytes one request's materialized tree may spend, as the operator CONFIGURED it (knob
 *  `materializationHeapBytes`), where **0 means "derive it from spare JVM heap"**. Asked for at every admission and
 *  never captured at construction, so a budget raised while the daemon runs is obeyed by the next request.
 *
 *  Only [SpliceConfig] and the materialization gate's own default produce one. Wired where a [MaterializedByteCap]
 *  is expected, the 0 an untouched install reads would refuse every request as too large. */
public fun interface MaterializationBudgetBytes {
    public operator fun invoke(): Long
}

/** The ceiling on ONE request's materialized weight, in bytes — a [MaterializationBudgetBytes] capped by the
 *  process heap ledger, so it is ALWAYS POSITIVE. Asked for at every request by the two layers that enforce it, so
 *  a budget raised while the daemon runs admits the next body both would have answered 413.
 *
 *  The materialization gate is the only producer: it owns the ledger and the "0 means derive" rule, and the ingress
 *  guard in front of the head asks the gate's own reader rather than deriving a second copy, so the 413 the
 *  transport writes names the number the gate would have refused on. It lives here in core, like [RequestByteCap],
 *  because the enforcers sit in two different modules. */
public fun interface MaterializedByteCap {
    public operator fun invoke(): Long
}
