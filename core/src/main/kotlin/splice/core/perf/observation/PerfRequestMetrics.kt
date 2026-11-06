package splice.core.perf.observation

internal object PerfRequestMetrics {
    private val terminal = setOf(PerfEventKind.REQUEST_FINISHED)
    private val content = setOf(PerfEventKind.CONTENT_PROGRESS, PerfEventKind.REQUEST_FINISHED)
    private val attempt = setOf(PerfEventKind.ATTEMPT_FINISHED)

    fun definitions(): List<PerfMetricDefinition> = durations() + quantities() + lifecycle()

    private fun durations(): List<PerfMetricDefinition> = listOf(
        duration("arrival_to_terminal_ms", REQUEST_ARRIVAL, "honest client terminal", terminal),
        duration("arrival_to_first_meaningful_content_ms", REQUEST_ARRIVAL, "first meaningful content", content),
        duration("arrival_to_first_reasoning_ms", REQUEST_ARRIVAL, "first reasoning content", content),
        duration("arrival_to_first_text_ms", REQUEST_ARRIVAL, "first text content", content),
        duration("arrival_to_first_tool_ms", REQUEST_ARRIVAL, "first tool content", content),
        duration("queue_wait_ms", "queue entry", "admission or terminal", terminal + PerfEventKind.REQUEST_ADMITTED),
        duration("attempt_duration_ms", "attempt start", "attempt terminal", attempt),
        duration("upstream_gap_ms", "upstream progress", "next upstream progress", setOf(PerfEventKind.GAP)),
        duration("downstream_gap_ms", "client progress", "next client progress", setOf(PerfEventKind.GAP)),
        duration(
            "local_read_block_ms",
            "synchronous event delivery begins",
            "event delivery returns to reader",
            setOf(PerfEventKind.GAP),
        ),
        duration(
            "client_write_flush_ms",
            "client write begins",
            "successful flush",
            setOf(PerfEventKind.CLIENT_PROGRESS),
        ),
        duration("upstream_gap_max_ms", REQUEST_ARRIVAL, "largest upstream gap per request", terminal),
        duration("downstream_gap_max_ms", REQUEST_ARRIVAL, "largest client gap per request", terminal),
    )

    private fun duration(
        id: String,
        origin: String,
        end: String,
        events: Set<PerfEventKind>,
    ): PerfMetricDefinition = PerfMetricDefinition(
        id,
        "$origin to $end",
        PerfMetricType.HISTOGRAM,
        "ms",
        origin,
        end,
        if (events == terminal || events == content) {
            "completed requests; attributed at terminal"
        } else {
            "observed events"
        },
        events,
    )

    private fun quantities(): List<PerfMetricDefinition> = listOf(
        quantity("upstream_bytes", "bytes", PerfEventKind.PROTOCOL_PROGRESS),
        quantity("downstream_bytes", "bytes", PerfEventKind.CLIENT_PROGRESS),
        quantity("upstream_events", "events", PerfEventKind.PROTOCOL_PROGRESS),
        quantity("downstream_events", "events", PerfEventKind.CLIENT_PROGRESS),
        quantity("input_tokens", "tokens", PerfEventKind.USAGE_RECORDED),
        quantity("output_tokens", "tokens", PerfEventKind.USAGE_RECORDED),
        quantity("cached_tokens", "tokens", PerfEventKind.USAGE_RECORDED),
        quantity("missing_usage", "requests", PerfEventKind.USAGE_RECORDED),
    )

    private fun quantity(id: String, unit: String, event: PerfEventKind): PerfMetricDefinition = PerfMetricDefinition(
        id,
        quantityDescription(id),
        PerfMetricType.COUNTER,
        unit,
        "observed event",
        "observed event",
        "events at observation time; tokens at request terminal",
        if (event == PerfEventKind.USAGE_RECORDED) setOf(event, PerfEventKind.REQUEST_FINISHED) else setOf(event),
    )

    private fun quantityDescription(id: String): String = if (id == "upstream_bytes") {
        "HTTP positive response-body reads including error bodies, or UTF-8 decoded WebSocket text callbacks; " +
            "excludes transport/TLS framing; fragmented or malformed protocol bytes remain observed"
    } else {
        "Observed $id; absent is unknown, never an inferred zero"
    }

    private fun lifecycle(): List<PerfMetricDefinition> {
        val counts =
            listOf(
                "requests_arrived", "requests_admitted", "requests_finished", "requests_good", "requests_bad",
                "requests_unknown", "attempts_started", "attempts_finished", "tools_started", "tools_finished",
            )
        val gauges = listOf("requests_live", "attempts_live", "tools_live")
        return counts.map {
            PerfMetricDefinition(
                it,
                "Lifecycle events, not caller-supplied cumulative totals",
                PerfMetricType.COUNTER,
                "events",
                "lifecycle transition",
                "lifecycle transition",
                "events at transition time",
            )
        } + gauges.map {
            PerfMetricDefinition(
                it,
                "Live lifecycle handles; separate from gate permits",
                PerfMetricType.GAUGE,
                "handles",
                "lifecycle transition",
                "next transition or sample",
                "time-weighted live handles",
            )
        }
    }
}

private const val REQUEST_ARRIVAL = "request arrival"
