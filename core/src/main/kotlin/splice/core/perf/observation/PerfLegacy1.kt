package splice.core.perf.observation

import splice.core.perf.PerfKeys

internal object PerfLegacy1 {
    fun definitions(): List<PerfMetricDefinition> =
        part1() + part2() + part3() + part4() + part5() + part6() + part7() + part8()

    private fun part1(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.RECV,
            "ms",
            "Legacy recv completion mark from TurnPerf construction (legacy origin)",
        ),
        entry(
            PerfKeys.PARSE,
            "ms",
            "Legacy parse completion mark from TurnPerf construction (legacy origin)",
        ),
        entry(
            PerfKeys.BUILD,
            "ms",
            "Legacy build completion mark from TurnPerf construction (legacy origin)",
        ),
        entry(
            PerfKeys.GATE,
            "ms",
            "Legacy gate completion mark from TurnPerf construction (legacy origin)",
        ),
        entry(
            PerfKeys.HEADERS,
            "ms",
            "Legacy headers completion mark from TurnPerf construction (legacy origin)",
        ),
    )

    private fun part2(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.FIRST_BYTE,
            "ms",
            "Legacy first upstream byte stage completion from TurnPerf construction; not meaningful " +
                "model content or first client byte",
        ),
        entry(
            PerfKeys.FIRST_FRAME,
            "ms",
            "Legacy first_frame completion mark from TurnPerf construction (legacy origin)",
        ),
        entry(
            PerfKeys.FIRST_DELTA,
            "ms",
            "Legacy first_delta completion mark from TurnPerf construction (legacy origin)",
        ),
        entry(
            PerfKeys.STREAM_END,
            "ms",
            "Legacy stream_end completion mark from TurnPerf construction (legacy origin)",
        ),
        entry(
            PerfKeys.FINISH,
            "ms",
            "Legacy finish completion mark from TurnPerf construction (legacy origin)",
        ),
    )

    private fun part3(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.TOTAL,
            "ms",
            "Legacy total stage mark from TurnPerf construction to finish; excludes route " +
                "pre-admission; not arrival_to_terminal_ms",
        ),
        entry(
            PerfKeys.ADMIT_WAIT_MS,
            "ms",
            "Legacy admit_wait_ms summed elapsed milliseconds; no new event value is inferred",
        ),
        entry(
            PerfKeys.PRE_ADMISSION_MS,
            "ms",
            "Route entry to the legacy stage-clock origin, including dispatch, auth and " +
                "model-roster waits.",
        ),
        entry(
            PerfKeys.MODEL_ROSTER_WAIT_MS,
            "ms",
            "The model-roster portion of PRE_ADMISSION_MS, before an inflight slot is acquired.",
        ),
        entry(
            PerfKeys.ADMIT_HELD_MS,
            "ms",
            "Only on a request the gate HELD because its command's limit was full: how long it sat " +
                "in the queue. A request that went straight in has no such field, whatever " +
                "ADMIT_WAIT_MS reads, since a pass through an open gate is not waiting. \"Waited in " +
                "line\" counts and lists the requests that carry it (Marlin, Oct 12).",
        ),
    )

    private fun part4(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.LEASE_WAIT_MS,
            "ms",
            "Legacy lease_wait_ms summed elapsed milliseconds; no new event value is inferred",
        ),
        entry(
            PerfKeys.PREP_MS,
            "ms",
            "Legacy prep_ms summed elapsed milliseconds; no new event value is inferred",
        ),
        entry(
            PerfKeys.ACCOUNT_CHECKS_MS,
            "ms",
            "Elapsed account evidence and per-call policy admission, including any wait, not CPU " +
                "time.",
        ),
        entry(
            PerfKeys.CREDENTIAL_HOLD_MS,
            "ms",
            "Elapsed credential hold admission, separate from per-attempt AUTH_MS.",
        ),
        entry(
            PerfKeys.SIZE_PREFLIGHT_MS,
            "ms",
            "Elapsed measured-input size preflight before the drive begins.",
        ),
    )

    private fun part5(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.OUTBOUND_PREP_MS,
            "ms",
            "Elapsed local request rendering/compression before an upstream attempt.",
        ),
        entry(
            PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS,
            "ms",
            "Legacy arrival_to_first_client_byte_ms summed elapsed milliseconds; no new event value " +
                "is inferred",
        ),
        entry(
            PerfKeys.ARRIVAL_TO_UPSTREAM_WRITE_MS,
            "ms",
            "Arrival to the final successfully flushed request byte of the latest upstream attempt.",
        ),
        entry(
            PerfKeys.UPSTREAM_WRITE_TO_FIRST_BYTE_MS,
            "ms",
            "Final SSE request-body flush to the first positive upstream body read.",
        ),
        entry(
            PerfKeys.ARRIVAL_TO_WS_SEND_ACCEPTED_MS,
            "ms",
            "Arrival to JDK send-future acceptance, not socket or TLS drain.",
        ),
    )

    private fun part6(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.WS_SEND_ACCEPTED_TO_FIRST_FRAGMENT_MS,
            "ms",
            "JDK send acceptance to the first decoded text fragment, before JSON event assembly.",
        ),
        entry(
            PerfKeys.WS_REFUSED_TOO_LARGE,
            PerfLegacyMetrics.COUNT_UNIT,
            "WebSocket attempts whose peer refused the request frame as too large: a 1009 close " +
                "before any event.",
        ),
        entry(
            PerfKeys.WS_PRE_OUTPUT_FALLBACKS,
            PerfLegacyMetrics.COUNT_UNIT,
            "WebSocket rounds re-served over HTTP/SSE before any client bytes, after the " +
                "fresh-socket retry failed too.",
        ),
        entry(
            PerfKeys.WS_FRESH_SOCKET_RETRIES,
            PerfLegacyMetrics.COUNT_UNIT,
            "WebSocket rounds that failed before client bytes on a socket fault and were sent again " +
                "at once on a fresh socket.",
        ),
        entry(
            PerfKeys.WS_TRANSPORT_DECLINES,
            PerfLegacyMetrics.COUNT_UNIT,
            "WebSocket rounds the socket could not take at all: its connect, its send or its first " +
                "event failed.",
        ),
    )

    private fun part7(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.WS_PREFILL_ROUNDS,
            PerfLegacyMetrics.COUNT_UNIT,
            "Legacy ws_prefill_rounds observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.WS_PREFILL_BYPASSES,
            PerfLegacyMetrics.COUNT_UNIT,
            "Legacy ws_prefill_bypasses observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.ARRIVAL_TO_UPSTREAM_HEADERS_START_MS,
            "ms",
            "OkHttp responseHeadersStart receipt on the arrival clock, before Ktor's response " +
                "handoff.",
        ),
        entry(
            PerfKeys.UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS,
            "ms",
            "Header receipt on OkHttp's call thread to Ktor's response handler, both on the arrival " +
                "clock.",
        ),
        entry(
            PerfKeys.AUTH_MS,
            "ms",
            "Legacy auth_ms summed elapsed milliseconds; no new event value is inferred",
        ),
    )

    private fun part8(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.BACKOFF_MS,
            "ms",
            "Legacy backoff_ms summed elapsed milliseconds; no new event value is inferred",
        ),
        entry(
            PerfKeys.REFRESH_MS,
            "ms",
            "Legacy refresh_ms summed elapsed milliseconds; no new event value is inferred",
        ),
        entry(
            PerfKeys.WRITE_MS,
            "ms",
            "Legacy write_ms summed elapsed milliseconds; no new event value is inferred",
        ),
    )

    private fun entry(id: String, unit: String, description: String): PerfMetricDefinition = PerfMetricDefinition(
        id,
        description,
        PerfMetricType.LEGACY,
        unit,
        "legacy row clock or count",
        "legacy row boundary",
        "legacy completed rows; absence is never synthesized",
        projections = setOf(PerfProjection.LOCAL, PerfProjection.SUPPORT),
    )
}
