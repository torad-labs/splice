package splice.head.perf

/** Why a turn failed and how hard the retry loop tried, beside the outcome tag on its perf row. */
public data class PerfFailure(
    /** V4-117: WHY this turn failed, as the taxonomy's cause, so a perf row can be grouped by cause
     *  rather than by the wire type the client happened to be told (the two differ by design — see
     *  WireType). Null for a turn that did not fail. */
    val cause: String? = null,
    /** V4-117: how many upstream attempts the retry loop made, as RECORDED by the loop itself.
     *  Written only when it is non-zero, so a row without retries looks exactly as it did before
     *  this field existed — the alternative would put layers=0 on every success in the file. */
    val layers: Int = 0,
)
