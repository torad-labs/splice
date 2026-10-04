// NEW: V4-456 — exhaustive transport pairs keep JDK acceptance separate from observed SSE writes.
package splice.core.perf

/** The observed boundaries for one attempt, never interchangeable between transports. */
public enum class UpstreamMilestones(public val arrival: String, public val wait: String) {
    SSE_WRITE(PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS, PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS),
    WS_ACCEPTANCE(PerfKeys.ARRIVAL_TO_WS_SEND_ACCEPTED_MS, PerfKeys.WS_SEND_ACCEPTED_TO_FIRST_FRAGMENT_MS),
    SSE_HEADERS(
        PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS,
        PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS,
    ),
}
