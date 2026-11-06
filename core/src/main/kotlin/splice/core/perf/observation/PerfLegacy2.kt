package splice.core.perf.observation

import splice.core.perf.PerfKeys

internal object PerfLegacy2 {
    fun definitions(): List<PerfMetricDefinition> =
        part1() + part2() + part3() + part4() + part5() + part6() + part7() + part8()

    private fun part1(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.USAGE_MS,
            "ms",
            "Legacy usage_ms summed elapsed milliseconds; no new event value is inferred",
        ),
        entry(
            PerfKeys.ATTEMPTS,
            COUNT,
            "Legacy attempts observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.TRANSPORT_ATTEMPT_STARTS,
            COUNT,
            "Transport attempts begun, including attempts abandoned before their first event.",
        ),
        entry(
            PerfKeys.REFUSED_RUNTIME_PORT,
            COUNT,
            "Port of a proven refused connect to a loopback runtime, absent for resets and remote " +
                "failures.",
        ),
        entry(
            PerfKeys.NO_REQUEST,
            COUNT,
            "UsageHistory explicitly owns no final upstream request; absent token observations are " +
                "not missing bills.",
        ),
    )

    private fun part2(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.LOCAL_STEP,
            COUNT,
            "One client-facing code-mode step synthesized without an upstream post, not a turn.",
        ),
        entry(
            PerfKeys.ACTIVITY_QUERY,
            COUNT,
            "Claude Code's activity side query, answered by the head itself with no model: a local " +
                "step of its own kind.",
        ),
        entry(
            PerfKeys.COMPARE,
            COUNT,
            "A row written by a Compare models send (POST /api/playground): it has no session and " +
                "is no project's work, so a project view must never fold it into \"no project\". It " +
                "counts everywhere else a request counts.",
        ),
        entry(
            PerfKeys.CODE_MODE_CANONICAL_MS,
            "ms",
            "Elapsed milliseconds inside canonical history passes, summed on the owning turn.",
        ),
        entry(
            PerfKeys.CODE_MODE_TURN_LOCK_WAIT_MS,
            "ms",
            "Elapsed milliseconds acquiring the code-mode conversation turn lock, including " +
                "cancelled waits.",
        ),
    )

    private fun part3(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.CODE_MODE_DIVERGENCE,
            COUNT,
            "A changed code-mode callback was sent upstream without touching the owner's queued " +
                "script.",
        ),
        entry(
            PerfKeys.CODE_MODE_START_REJECTED,
            COUNT,
            "Failed executable source released a pending runtime boot or disposed its unadopted " +
                "cell.",
        ),
        entry(
            PerfKeys.RETRIES,
            COUNT,
            "Legacy retries observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.REFRESHES,
            COUNT,
            "Legacy refreshes observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.EMPTY_RETRIES,
            COUNT,
            "Rounds re-sent once, same body, after the model answered with nothing " +
                "(EmptyAnswerRetry).",
        ),
    )

    private fun part4(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.EMPTY_CONTINUES,
            COUNT,
            "Rounds carried on from their own reasoning, with a request for the answer, after the " +
                "model answered with reasoning alone (EmptyAnswerRetry, on a vendor whose quirks ask " +
                "for it).",
        ),
        entry(
            PerfKeys.EARLIEST_RESET_EPOCH_SECONDS,
            SECONDS,
            "The exact instant a spent window comes back, never the capped client retry deadline: " +
                "the selector's earliest exhausted-account reset when admission turned the request " +
                "away, and the plan window's own reset on every other turn that ended error:plan-limit " +
                "(V4-444).",
        ),
        entry(
            PerfKeys.RESUME_AT_EPOCH_SECONDS,
            SECONDS,
            "A provider-stated retry or resume instant, including Retry-After; it need not describe " +
                "a spent window.",
        ),
        entry(
            PerfKeys.PROVIDER_STATUS,
            COUNT,
            "V4-444: the HTTP status the provider's own answer carried, for a turn whose failure " +
                "came back as a response. ABSENT when no answer came back at all ; a transport failure, " +
                "or a local hold that refused the turn before it was sent ; because 0 there reads as a " +
                "status the provider returned. The outcome tag says what splice made of it; this says " +
                "what the provider replied, which is the fact the console's Requests list could not " +
                "show.",
        ),
        entry(
            PerfKeys.LIMIT_WINDOW_SECONDS,
            SECONDS,
            "V4-444: how long the spent plan window is, in SECONDS, beside " +
                "[EARLIEST_RESET_EPOCH_SECONDS]'s instant when it comes back. Converted at the provider " +
                "that reads it (codex writes `limit_window_minutes`), so no vendor unit reaches this " +
                "file. Absent when the provider did not say, which is every provider but codex today.",
        ),
    )

    private fun part5(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.REQ_BYTES,
            BYTES,
            "Legacy req_bytes observed bytes; no new event value is inferred",
        ),
        entry(
            PerfKeys.UPSTREAM_REQ_BYTES,
            BYTES,
            "Legacy upstream_req_bytes observed bytes; no new event value is inferred",
        ),
        entry(
            PerfKeys.SSE_BYTES_IN,
            BYTES,
            "Legacy sse_bytes_in observed bytes; no new event value is inferred",
        ),
        entry(
            PerfKeys.EVENTS_IN,
            COUNT,
            "Legacy events_in observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.UP_GAP_MAX_MS,
            "ms",
            "POST-to-first-event wait and later reader-side silence, excluding synchronous " +
                "downstream delivery.",
        ),
    )

    private fun part6(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.UP_GAPS_2S,
            COUNT,
            "Legacy up_gaps_2s observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.UP_BLOCKED_MAX_MS,
            "ms",
            "Legacy up_blocked_max_ms summed elapsed milliseconds; no new event value is inferred",
        ),
        entry(
            PerfKeys.UP_BLOCKED_TOTAL_MS,
            "ms",
            "Total synchronous downstream delivery time, including suspension, across every " +
                "observed event.",
        ),
        entry(
            PerfKeys.UP_GAP_END,
            "enum",
            "Legacy up_gap_end observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.UP_CONTENT_GAP_MAX_MS,
            "ms",
            "Longest upstream wait for content, including ping-only stretches and terminal tails, " +
                "excluding delivery.",
        ),
    )

    private fun part7(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.OUT_HOLD_MAX_MS,
            "ms",
            "Longest pacer residence and gap between successful client frame writes.",
        ),
        entry(
            PerfKeys.OUT_GAP_MAX_MS,
            "ms",
            "Legacy out_gap_max_ms summed elapsed milliseconds; no new event value is inferred",
        ),
        entry(
            PerfKeys.OUT_TICK_LATE_MAX_MS,
            "ms",
            "Longest a pacer release tick ran past its 16 ms schedule, counted on turns whose " +
                "frames that tick released.",
        ),
        entry(
            PerfKeys.ARRIVAL_BURSTS,
            COUNT,
            "Visible deltas grouped into bursts by arrival gap before the pacer: the bursts, the " +
                "largest in deltas, and the longest silence a burst broke (the first burst of a turn " +
                "follows no silence and records none).",
        ),
        entry(
            PerfKeys.ARRIVAL_BURST_MAX_DELTAS,
            COUNT,
            "Legacy arrival_burst_max_deltas observed value; no new event value is inferred",
        ),
    )

    private fun part8(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.ARRIVAL_SILENCE_MAX_MS,
            "ms",
            "Legacy arrival_silence_max_ms summed elapsed milliseconds; no new event value is " +
                "inferred",
        ),
        entry(
            PerfKeys.UP_GAP_MAX_START_EPOCH_MS,
            "ms",
            "Epoch starts belong to the same winning intervals as their corresponding maxima.",
        ),
        entry(
            PerfKeys.OUT_HOLD_MAX_START_EPOCH_MS,
            "ms",
            "Legacy out_hold_max_start_epoch_ms summed elapsed milliseconds; no new event value is " +
                "inferred",
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

private const val COUNT = "count"

private const val SECONDS = "seconds"

private const val BYTES = "bytes"
