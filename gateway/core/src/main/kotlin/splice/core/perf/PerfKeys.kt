// NEW: the single source of every perf field name. Split from TurnPerf.kt
// so the recorder is not billed for the key catalogue (concentration, 2026-08-19).
package splice.core.perf

/** The single source of every perf field name (marks are *_ms-since-arrival; counters are raw). */
public object PerfKeys {
    // stage completion marks (ms since arrival)
    public const val RECV: String = "recv"
    public const val PARSE: String = "parse"
    public const val BUILD: String = "build"
    public const val GATE: String = "gate"
    public const val HEADERS: String = "headers"
    public const val FIRST_BYTE: String = "first_byte"
    public const val FIRST_FRAME: String = "first_frame"
    public const val FIRST_DELTA: String = "first_delta"
    public const val STREAM_END: String = "stream_end"
    public const val FINISH: String = "finish"
    public const val TOTAL: String = "total"

    // counters (durations are summed ms; sizes are bytes; the rest are counts)
    public const val AUTH_MS: String = "auth_ms"
    public const val BACKOFF_MS: String = "backoff_ms"
    public const val REFRESH_MS: String = "refresh_ms"
    public const val WRITE_MS: String = "write_ms"
    public const val USAGE_MS: String = "usage_ms"
    public const val ATTEMPTS: String = "attempts"
    public const val RETRIES: String = "retries"
    public const val REFRESHES: String = "refreshes"
    public const val REQ_BYTES: String = "req_bytes"
    public const val UPSTREAM_REQ_BYTES: String = "upstream_req_bytes"
    public const val SSE_BYTES_IN: String = "sse_bytes_in"
    public const val EVENTS_IN: String = "events_in"
    public const val FRAMES_OUT: String = "frames_out"

    /** Frames that carried CONTENT — everything except the structural turn-opening pair
     *  (message_start + ping). G5's safe-reissue probe keys off THIS, not [FRAMES_OUT]: since
     *  message_start moved to upstream-handoff (dead-air fix), frames_out goes non-zero before the
     *  client has seen a single token, which would silently disable pre-content reissue and
     *  downgrade a torn stream from a retryable overloaded_error to a raw api_error. Reissuing
     *  after only the opening pair is safe — ensureStarted() is idempotent, so nothing duplicates. */
    public const val CONTENT_FRAMES_OUT: String = "content_frames_out"
    public const val FRAMES_SKIPPED: String = "frames_skipped"
    public const val BYTES_OUT: String = "bytes_out"
    public const val OUT_TOKENS: String = "out_tokens"
    public const val IN_TOKENS: String = "in_tokens"
    public const val CACHED_TOKENS: String = "cached_tokens"

    /** Concurrent turns in flight on this head at admission — the live-concurrency gauge. */
    public const val INFLIGHT: String = "inflight"

    /** IO-005: AsyncFileIo.droppedCount() at admission — the cumulative process-wide count of
     *  state/telemetry writes dropped at queue capacity, riding every turn's perf row the same way
     *  [INFLIGHT] rides a live gauge. A one-time stderr warning at the first drop otherwise leaves
     *  every later drop silent; this makes the running total watchable via /mgmt/logs + the perf
     *  aggregation without polling a new endpoint. */
    public const val ASYNC_IO_DROPS: String = "async_io_drops"

    // Tool-surface deferral (responses-lite tool_search) — the expected-delta instrument (#959):
    // a deploy where TOOLS_DEFERRED stays 0 is a false landing, not a quiet success.
    public const val TOOLS_EAGER: String = "tools_eager"
    public const val TOOLS_DEFERRED: String = "tools_deferred"
    public const val SEARCH_ROUNDS: String = "search_rounds"

    /** UP-004: retries of a SocketException/SocketTimeoutException that fired AFTER the request
     *  body was (possibly) fully written — the upstream may already be processing/billing the
     *  prior attempt. Diagnostics were label-only ("transport-possible-duplicate" in the retry
     *  log); this makes the double-issue rate countable in the perf row, not only greppable. */
    public const val POST_SEND_RETRIES: String = "post_send_retries"

    /** Mark keys in pipeline order — the aggregation and the log line render in THIS order. */
    public val markOrder: List<String> = listOf(
        RECV, PARSE, BUILD, GATE, HEADERS, FIRST_BYTE, FIRST_FRAME, FIRST_DELTA,
        STREAM_END, FINISH, TOTAL,
    )
}
