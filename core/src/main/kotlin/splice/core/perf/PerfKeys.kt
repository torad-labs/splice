// NEW: the single source of every perf field name. Split from TurnPerf.kt
// so the recorder is not billed for the key catalogue (concentration, 2026-08-19).
package splice.core.perf

/** Only event kinds, never provider content, may identify the end of an upstream silence. */
public enum class UpstreamGapEnd {
    THINKING_DELTA,
    TEXT_DELTA,
    INPUT_JSON_DELTA,
    TOOL_INPUT_DELTA,
    CONTENT_BLOCK_START,
    CONTENT_BLOCK_STOP,
    MESSAGE_START,
    MESSAGE_STOP,
    PING,
    MESSAGE_DELTA,
    COMPLETED,
    TORN,
    CANCELLED,
    UNKNOWN,
    ;

    public val wire: String = name.lowercase()
}

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
    public const val ADMIT_WAIT_MS: String = "admit_wait_ms"
    public const val LEASE_WAIT_MS: String = "lease_wait_ms"
    public const val PREP_MS: String = "prep_ms"
    public const val ARRIVAL_TO_FIRST_CLIENT_BYTE_MS: String = "arrival_to_first_client_byte_ms"

    /** Arrival to the final successfully flushed request byte of the latest upstream attempt. */
    public const val ARRIVAL_TO_UPSTREAM_WRITE_MS: String = "arrival_to_upstream_write_ms"

    /** Final SSE request-body flush to the first positive upstream body read. */
    public const val UPSTREAM_WRITE_TO_FIRST_BYTE_MS: String = "upstream_write_to_first_byte_ms"

    /** Arrival to JDK send-future acceptance, not socket or TLS drain. */
    public const val ARRIVAL_TO_WS_SEND_ACCEPTED_MS: String = "arrival_to_ws_send_accepted_ms"

    /** JDK send acceptance to the first decoded text fragment, before JSON event assembly. */
    public const val WS_SEND_ACCEPTED_TO_FIRST_FRAGMENT_MS: String = "ws_send_accepted_to_first_fragment_ms"

    /** WebSocket attempts whose peer refused the request frame as too large: a 1009 close before any event. */
    public const val WS_REFUSED_TOO_LARGE: String = "ws_refused_too_large"

    /** OkHttp responseHeadersStart receipt on the arrival clock, before Ktor's response handoff. */
    public const val ARRIVAL_TO_UPSTREAM_HEADERS_START_MS: String = "arrival_to_upstream_headers_start_ms"

    /** Header receipt on OkHttp's call thread to Ktor's response handler, both on the arrival clock. */
    public const val UPSTREAM_HEADERS_START_TO_KTOR_HEADERS_MS: String = "upstream_headers_start_to_ktor_headers_ms"
    public const val AUTH_MS: String = "auth_ms"
    public const val BACKOFF_MS: String = "backoff_ms"
    public const val REFRESH_MS: String = "refresh_ms"
    public const val WRITE_MS: String = "write_ms"
    public const val USAGE_MS: String = "usage_ms"
    public const val ATTEMPTS: String = "attempts"

    /** Transport attempts begun, including attempts abandoned before their first event. */
    public const val TRANSPORT_ATTEMPT_STARTS: String = "transport_attempt_starts"

    /** Port of a proven refused connect to a loopback runtime, absent for resets and remote failures. */
    public const val REFUSED_RUNTIME_PORT: String = "refused_runtime_port"

    /** UsageHistory explicitly owns no final upstream request; absent token observations are not missing bills. */
    public const val NO_REQUEST: String = "no_request"

    /** One client-facing code-mode step synthesized without an upstream post, not a turn. */
    public const val LOCAL_STEP: String = "local_step"

    /** Claude Code's activity side query, answered by the head itself with no model: a local step of its own kind. */
    public const val ACTIVITY_QUERY: String = "activity_query"

    /** Elapsed milliseconds inside canonical history passes, summed on the owning turn. */
    public const val CODE_MODE_CANONICAL_MS: String = "code_mode_canonical_ms"

    /** Elapsed milliseconds acquiring the code-mode conversation turn lock, including cancelled waits. */
    public const val CODE_MODE_TURN_LOCK_WAIT_MS: String = "code_mode_turn_lock_wait_ms"

    /** A changed code-mode callback was sent upstream without touching the owner's queued script. */
    public const val CODE_MODE_DIVERGENCE: String = "code_mode_divergence"

    /** Failed executable source released a pending runtime boot or disposed its unadopted cell. */
    public const val CODE_MODE_START_REJECTED: String = "code_mode_start_rejected"

    public const val RETRIES: String = "retries"
    public const val REFRESHES: String = "refreshes"

    /** The exact instant a spent window comes back, never the capped client retry deadline: the selector's earliest
     *  exhausted-account reset when admission turned the request away, and the plan window's own reset on every other
     *  turn that ended error:plan-limit (V4-444). */
    public const val EARLIEST_RESET_EPOCH_SECONDS: String = "earliest_reset_epoch_seconds"

    /** V4-444: the HTTP status the provider's own answer carried, for a turn whose failure came back as a response.
     *  ABSENT when no answer came back at all — a transport failure, or a local hold that refused the turn before it
     *  was sent — because 0 there reads as a status the provider returned. The outcome tag says what splice made of
     *  it; this says what the provider replied, which is the fact the console's Requests list could not show. */
    public const val PROVIDER_STATUS: String = "provider_status"

    /** V4-444: how long the spent plan window is, in SECONDS, beside [EARLIEST_RESET_EPOCH_SECONDS]'s instant when it
     *  comes back. Converted at the provider that reads it (codex writes `limit_window_minutes`), so no vendor unit
     *  reaches this file. Absent when the provider did not say, which is every provider but codex today. */
    public const val LIMIT_WINDOW_SECONDS: String = "limit_window_seconds"
    public const val REQ_BYTES: String = "req_bytes"
    public const val UPSTREAM_REQ_BYTES: String = "upstream_req_bytes"
    public const val SSE_BYTES_IN: String = "sse_bytes_in"
    public const val EVENTS_IN: String = "events_in"

    /** POST-to-first-event wait and later reader-side silence, excluding synchronous downstream delivery. */
    public const val UP_GAP_MAX_MS: String = "up_gap_max_ms"
    public const val UP_GAPS_2S: String = "up_gaps_2s"
    public const val UP_BLOCKED_MAX_MS: String = "up_blocked_max_ms"
    public const val UP_GAP_END: String = "up_gap_end"

    /** Longest upstream wait for content, including ping-only stretches and terminal tails, excluding delivery. */
    public const val UP_CONTENT_GAP_MAX_MS: String = "up_content_gap_max_ms"

    /** Longest pacer residence and gap between successful client frame writes. */
    public const val OUT_HOLD_MAX_MS: String = "out_hold_max_ms"
    public const val OUT_GAP_MAX_MS: String = "out_gap_max_ms"

    /** Longest a pacer release tick ran past its 16 ms schedule, counted on turns whose frames that tick released. */
    public const val OUT_TICK_LATE_MAX_MS: String = "out_tick_late_max_ms"

    /** Visible deltas grouped into bursts by arrival gap before the pacer: the bursts, the largest in deltas, and the
     *  longest silence a burst broke (the first burst of a turn follows no silence and records none). */
    public const val ARRIVAL_BURSTS: String = "arrival_bursts"
    public const val ARRIVAL_BURST_MAX_DELTAS: String = "arrival_burst_max_deltas"
    public const val ARRIVAL_SILENCE_MAX_MS: String = "arrival_silence_max_ms"

    /** Epoch starts belong to the same winning intervals as their corresponding maxima. */
    public const val UP_GAP_MAX_START_EPOCH_MS: String = "up_gap_max_start_epoch_ms"
    public const val OUT_HOLD_MAX_START_EPOCH_MS: String = "out_hold_max_start_epoch_ms"

    /** Consecutive positive SSE read completions or WS text callback entries, not packet arrival. */
    public const val UP_WIRE_GAP_MAX_MS: String = "up_wire_gap_max_ms"
    public const val UP_WIRE_GAP_MAX_START_EPOCH_MS: String = "up_wire_gap_max_start_epoch_ms"

    /** SSE time inside a positive Source.read; WS demand-to-text callback time. */
    public const val UP_READ_WAIT_MAX_MS: String = "up_read_wait_max_ms"
    public const val UP_READ_WAIT_MAX_START_EPOCH_MS: String = "up_read_wait_max_start_epoch_ms"

    /** SSE positive-read return to next call; WS text callback entry to the following demand. */
    public const val UP_READ_IDLE_MAX_MS: String = "up_read_idle_max_ms"
    public const val UP_READ_IDLE_MAX_START_EPOCH_MS: String = "up_read_idle_max_start_epoch_ms"
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

    /** V4-85: prompt-cache WRITE tokens (cache_creation_input_tokens). A DISJOINT part of
     *  [IN_TOKENS], exactly as [CACHED_TOKENS] is — the row's `in_tokens` contains both — so the
     *  cache-MISS bucket is `in_tokens - cached_tokens - cache_write_tokens`. This key exists
     *  because a cache write bills at its own premium rate: without it SessionCost could only see
     *  the write folded inside `in_tokens` and charged it as a miss, leaving a head's declared
     *  cache_write rate as arithmetic over a permanently-zero operand. Written on every turn (0 on
     *  dialects whose wire reports no such bucket), the same way [CACHED_TOKENS] is. */
    public const val CACHE_WRITE_TOKENS: String = "cache_write_tokens"

    /** Oct 10, 2026 review: the share of [CACHE_WRITE_TOKENS] written at the 1-hour TTL, never an
     *  addition to it. Both vendors that report the split price an hourly write above a five-minute
     *  one (Anthropic 2x input against 1.25x, Moonshot K3 6 against 3), so pricing the whole bucket at
     *  the five-minute rate undercharged every turn that held a cache for an hour. Written only when
     *  positive, like [REASONING_TOKENS]: an absent key is a turn that wrote no hourly cache, or a row
     *  from before the counter, and both mean the write bucket bills at the five-minute rate. NOT a
     *  billing key: a row's completeness still turns on the four buckets alone. */
    public const val CACHE_WRITE_1H_TOKENS: String = "cache_write_1h_tokens"

    /** The rounds a turn billed before its final one ([splice.core.turn.AbsorbedRounds]). [IN_TOKENS] is
     *  the final round's input, the context; each absorbed round was a request of its own, billed and
     *  metered, so its buckets ride beside it. Written only when the turn absorbed a round, so an absent
     *  key reads as none, which is what every row written before them means. */
    public const val ABSORBED_ROUNDS: String = "absorbed_rounds"
    public const val ABSORBED_IN_TOKENS: String = "absorbed_in_tokens"
    public const val ABSORBED_CACHED_TOKENS: String = "absorbed_cached_tokens"
    public const val ABSORBED_CACHE_WRITE_TOKENS: String = "absorbed_cache_write_tokens"

    /** The absorbed rounds' share of [ABSORBED_CACHE_WRITE_TOKENS] at the 1-hour TTL, read like
     *  [CACHE_WRITE_1H_TOKENS] and written only when positive. */
    public const val ABSORBED_CACHE_WRITE_1H_TOKENS: String = "absorbed_cache_write_1h_tokens"

    /** The part of [OUT_TOKENS] the absorbed rounds produced; [OUT_TOKENS] stays the turn's whole output. */
    public const val ABSORBED_OUT_TOKENS: String = "absorbed_out_tokens"

    /** The reasoning part of [OUT_TOKENS] ([splice.core.turn.Usage.reasoningTokens]): a Responses model
     *  reports it inside its output-token details, so it is already counted in [OUT_TOKENS] and pricing
     *  must never add it again. Written only when the turn reported some, so an absent key is a row from
     *  a dialect that reports none, or from before this counter, and reads as null rather than zero. */
    public const val REASONING_TOKENS: String = "reasoning_tokens"

    /** Source rounds this turn cut while they still streamed ([splice.core.turn.Usage.cutRounds]): steering a
     *  parked code-mode script ends its round before the backend's terminal, so the tokens that round used are
     *  billed upstream and never reported here. Written only when the turn cut one, so an absent key reads as none. */
    public const val CUT_SOURCE_ROUNDS: String = "cut_source_rounds"

    /** The classified failure's retry decision: 1 is permanent, 0 retryable; absent when no decision was observed. */
    public const val FAILURE_PERMANENT: String = "failure_permanent"

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

    /** V4-116: mid-stream re-anchors this turn SPENT — the proxy resumed the turn from its own
     *  salvage after a stall or a tear. A COUNT, not a flag, because the question the next incident
     *  asks is "how many times did splice try before giving up", and the ending message reports the
     *  same number. Zero/absent on a turn that never re-anchored, which is the expected-delta
     *  instrument: a deploy where this stays 0 on a head that stalls is a false landing. */
    public const val REANCHORS: String = "reanchors"

    /** V4-444: how many of those [REANCHORS] re-POSTed the request VERBATIM, because nothing the client could see
     *  had been salvaged (thinking only, or a tear before the first text delta). The two are different things to the
     *  person reading the row and splice has always known which it did -- the runner compares the continuation
     *  against the body it just posted, one line before the counter moves -- but only the journal said so, so a
     *  surface reading the row could not tell an answer RESUMED from a round STARTED OVER. Absent on a turn whose
     *  every continuation carried a partial, which is the common case. */
    public const val REANCHORS_FROM_SCRATCH: String = "reanchors_from_scratch"

    /** V4-116: the upstream SILENCE that triggered each re-anchor, summed in ms — the watchdog's own
     *  `idleMs` at the moment it fired, so the row answers "silent how long" without archaeology
     *  through the journal. Paired with [REANCHORS] on purpose: neither number is interpretable
     *  alone (one POST after 9 minutes of silence and five POSTs after 20 seconds each are opposite
     *  diagnoses), and a stall judged by the mid-output stall tier reports a value at that tier, not
     *  the 300s streamIdle an operator would otherwise assume. A SUM like every other duration
     *  counter here ([BACKOFF_MS], [REFRESH_MS]); a turn with two stalls reports both. */
    public const val STALL_MS: String = "stall_ms"

    /** Mark keys in pipeline order — the aggregation and the log line render in THIS order. */
    public val markOrder: List<String> = listOf(
        RECV, PARSE, BUILD, GATE, HEADERS, FIRST_BYTE, FIRST_FRAME, FIRST_DELTA,
        STREAM_END, FINISH, TOTAL,
    )
}
