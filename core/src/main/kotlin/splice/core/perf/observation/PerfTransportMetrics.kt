package splice.core.perf.observation

import splice.core.perf.PerfKeys

internal object PerfTransportMetrics {
    fun definitions(): List<PerfMetricDefinition> = listOf(
        PerfMetricDefinition(
            PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS,
            "Request arrival to first successful nonempty client write and flush; opening protocol frames count",
            PerfMetricType.HISTOGRAM,
            "ms",
            "request arrival",
            "first successful client byte flush",
            "completed client requests; independent of meaningful content",
            setOf(PerfEventKind.CLIENT_PROGRESS, PerfEventKind.REQUEST_FINISHED),
        ),
        milestone("http_request_write_ms", "final successfully flushed HTTP request byte", "http"),
        milestone("http_response_headers_ms", "HTTP response header receipt", "http"),
        milestone("http_first_body_byte_ms", "first positive HTTP body read", "http"),
        milestone("ws_send_accepted_ms", "JDK WebSocket send-future acceptance; not socket drain", "websocket"),
        milestone("ws_first_fragment_ms", "first decoded WebSocket text callback; before JSON assembly", "websocket"),
    )

    private fun milestone(id: String, end: String, path: String): PerfMetricDefinition = PerfMetricDefinition(
        id,
        "Arrival-relative attempt milestone: $end",
        PerfMetricType.HISTOGRAM,
        "ms",
        "request arrival",
        end,
        "observed attempt milestones; one sample per attempt boundary",
        setOf(PerfEventKind.PROTOCOL_PROGRESS),
        paths = listOf(path),
    )
}
