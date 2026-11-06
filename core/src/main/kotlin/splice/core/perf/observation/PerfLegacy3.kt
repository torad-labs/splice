package splice.core.perf.observation

import splice.core.perf.PerfKeys

internal object PerfLegacy3 {
    fun definitions(): List<PerfMetricDefinition> = part1() + part2() + part3() + part4() + part5() + part6() + part7()

    private fun part1(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.UP_WIRE_GAP_MAX_MS,
            "ms",
            "Consecutive positive SSE read completions or WS text callback entries, not packet " +
                "arrival.",
        ),
        entry(
            PerfKeys.UP_WIRE_GAP_MAX_START_EPOCH_MS,
            "ms",
            "Legacy up_wire_gap_max_start_epoch_ms summed elapsed milliseconds; no new event value " +
                "is inferred",
        ),
        entry(
            PerfKeys.UP_READ_WAIT_MAX_MS,
            "ms",
            "SSE time inside a positive Source.read; WS demand-to-text callback time.",
        ),
        entry(
            PerfKeys.UP_READ_WAIT_MAX_START_EPOCH_MS,
            "ms",
            "Legacy up_read_wait_max_start_epoch_ms summed elapsed milliseconds; no new event value " +
                "is inferred",
        ),
        entry(
            PerfKeys.UP_READ_IDLE_MAX_MS,
            "ms",
            "SSE positive-read return to next call; WS text callback entry to the following demand.",
        ),
    )

    private fun part2(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.UP_READ_IDLE_MAX_START_EPOCH_MS,
            "ms",
            "Legacy up_read_idle_max_start_epoch_ms summed elapsed milliseconds; no new event value " +
                "is inferred",
        ),
        entry(
            PerfKeys.FRAMES_OUT,
            COUNT,
            "Legacy frames_out observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.CONTENT_FRAMES_OUT,
            COUNT,
            "Frames that carried CONTENT ; everything except the structural turn-opening pair " +
                "(message_start + ping). G5's safe-reissue probe keys off THIS, not [FRAMES_OUT]: since " +
                "message_start moved to upstream-handoff (dead-air fix), frames_out goes non-zero " +
                "before the client has seen a single token, which would silently disable pre-content " +
                "reissue and downgrade a torn stream from a retryable overloaded_error to a raw " +
                "api_error. Reissuing after only the opening pair is safe ; ensureStarted() is " +
                "idempotent, so nothing duplicates.",
        ),
        entry(
            PerfKeys.FRAMES_SKIPPED,
            COUNT,
            "Legacy frames_skipped observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.BYTES_OUT,
            BYTES,
            "Legacy bytes_out observed bytes; no new event value is inferred",
        ),
    )

    private fun part3(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.OUT_TOKENS,
            TOKENS,
            "Legacy out_tokens observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.IN_TOKENS,
            TOKENS,
            "Legacy in_tokens observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.CACHED_TOKENS,
            TOKENS,
            "Legacy cached_tokens observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.CACHE_WRITE_TOKENS,
            TOKENS,
            "V4-85: prompt-cache WRITE tokens (cache_creation_input_tokens). A DISJOINT part of " +
                "[IN_TOKENS], exactly as [CACHED_TOKENS] is ; the row's `in_tokens` contains both ; so " +
                "the cache-MISS bucket is `in_tokens - cached_tokens - cache_write_tokens`. This key " +
                "exists because a cache write bills at its own premium rate: without it SessionCost " +
                "could only see the write folded inside `in_tokens` and charged it as a miss, leaving a " +
                "head's declared cache_write rate as arithmetic over a permanently-zero operand. " +
                "Written on every turn (0 on dialects whose wire reports no such bucket), the same way " +
                "[CACHED_TOKENS] is.",
        ),
        entry(
            PerfKeys.CACHE_WRITE_1H_TOKENS,
            TOKENS,
            "Oct 10, 2026 review: the share of [CACHE_WRITE_TOKENS] written at the 1-hour TTL, " +
                "never an addition to it. Both vendors that report the split price an hourly write " +
                "above a five-minute one (Anthropic 2x input against 1.25x, Moonshot K3 6 against 3), " +
                "so pricing the whole bucket at the five-minute rate undercharged every turn that held " +
                "a cache for an hour. Written only when positive, like [REASONING_TOKENS]: an absent " +
                "key is a turn that wrote no hourly cache, or a row from before the counter, and both " +
                "mean the write bucket bills at the five-minute rate. NOT a billing key: a row's " +
                "completeness still turns on the four buckets alone.",
        ),
    )

    private fun part4(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.ABSORBED_ROUNDS,
            COUNT,
            "The rounds a turn billed before its final one ([splice.core.turn.AbsorbedRounds]). " +
                "[IN_TOKENS] is the final round's input, the context; each absorbed round was a request " +
                "of its own, billed and metered, so its buckets ride beside it. Written only when the " +
                "turn absorbed a round, so an absent key reads as none, which is what every row written " +
                "before them means.",
        ),
        entry(
            PerfKeys.ABSORBED_IN_TOKENS,
            TOKENS,
            "Legacy absorbed_in_tokens observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.ABSORBED_CACHED_TOKENS,
            TOKENS,
            "Legacy absorbed_cached_tokens observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.ABSORBED_CACHE_WRITE_TOKENS,
            TOKENS,
            "Legacy absorbed_cache_write_tokens observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.ABSORBED_CACHE_WRITE_1H_TOKENS,
            TOKENS,
            "The absorbed rounds' share of [ABSORBED_CACHE_WRITE_TOKENS] at the 1-hour TTL, read " +
                "like [CACHE_WRITE_1H_TOKENS] and written only when positive.",
        ),
    )

    private fun part5(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.ABSORBED_OUT_TOKENS,
            TOKENS,
            "The part of [OUT_TOKENS] the absorbed rounds produced; [OUT_TOKENS] stays the turn's " +
                "whole output.",
        ),
        entry(
            PerfKeys.REASONING_TOKENS,
            TOKENS,
            "The reasoning part of [OUT_TOKENS] ([splice.core.turn.Usage.reasoningTokens]): a " +
                "Responses model reports it inside its output-token details, so it is already counted " +
                "in [OUT_TOKENS] and pricing must never add it again. Written only when the turn " +
                "reported some, so an absent key is a row from a dialect that reports none, or from " +
                "before this counter, and reads as null rather than zero.",
        ),
        entry(
            PerfKeys.CUT_SOURCE_ROUNDS,
            COUNT,
            "Source rounds this turn cut while they still streamed " +
                "([splice.core.turn.Usage.cutRounds]): steering a parked code-mode script ends its " +
                "round before the backend's terminal, so the tokens that round used are billed upstream " +
                "and never reported here. Written only when the turn cut one, so an absent key reads as " +
                "none.",
        ),
        entry(
            PerfKeys.SOURCE_ATTEMPTS,
            COUNT,
            "The upstream tries made by the turn that posted a code-mode script's source, on the " +
                "row of a later client step that source's reset ended. That step made no try of its " +
                "own, so its [ATTEMPTS] stays 0 and this says the stream it waited on reset after this " +
                "many. Written only on such a step, so an absent key reads as none.",
        ),
        entry(
            PerfKeys.FAILURE_PERMANENT,
            COUNT,
            "The classified failure's retry decision: 1 is permanent, 0 retryable; absent when no " +
                "decision was observed.",
        ),
    )

    private fun part6(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.INFLIGHT,
            COUNT,
            "Concurrent turns in flight on this head at admission ; the live-concurrency gauge.",
        ),
        entry(
            PerfKeys.ASYNC_IO_DROPS,
            COUNT,
            "IO-005: AsyncFileIo.droppedCount() at admission ; the cumulative process-wide count of " +
                "state/telemetry writes dropped at queue capacity, riding every turn's perf row the " +
                "same way [INFLIGHT] rides a live gauge. A one-time stderr warning at the first drop " +
                "otherwise leaves every later drop silent; this makes the running total watchable via " +
                "/mgmt/logs + the perf aggregation without polling a new endpoint.",
        ),
        entry(
            PerfKeys.TOOLS_EAGER,
            COUNT,
            "Legacy tools_eager observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.TOOLS_DEFERRED,
            COUNT,
            "Legacy tools_deferred observed value; no new event value is inferred",
        ),
        entry(
            PerfKeys.SEARCH_ROUNDS,
            COUNT,
            "Legacy search_rounds observed value; no new event value is inferred",
        ),
    )

    private fun part7(): List<PerfMetricDefinition> = listOf(
        entry(
            PerfKeys.POST_SEND_RETRIES,
            COUNT,
            "UP-004: retries of a SocketException/SocketTimeoutException that fired AFTER the " +
                "request body was (possibly) fully written ; the upstream may already be " +
                "processing/billing the prior attempt. Diagnostics were label-only " +
                "(\"transport-possible-duplicate\" in the retry log); this makes the double-issue rate " +
                "countable in the perf row, not only greppable.",
        ),
        entry(
            PerfKeys.REANCHORS,
            COUNT,
            "V4-116: mid-stream re-anchors this turn SPENT ; the proxy resumed the turn from its " +
                "own salvage after a stall or a tear. A COUNT, not a flag, because the question the " +
                "next incident asks is \"how many times did splice try before giving up\", and the ending " +
                "message reports the same number. Zero/absent on a turn that never re-anchored, which " +
                "is the expected-delta instrument: a deploy where this stays 0 on a head that stalls is " +
                "a false landing.",
        ),
        entry(
            PerfKeys.REANCHORS_FROM_SCRATCH,
            COUNT,
            "V4-444: how many of those [REANCHORS] re-POSTed the request VERBATIM, because nothing " +
                "the client could see had been salvaged (thinking only, or a tear before the first text " +
                "delta). The two are different things to the person reading the row and splice has " +
                "always known which it did -- the runner compares the continuation against the body it " +
                "just posted, one line before the counter moves -- but only the journal said so, so a " +
                "surface reading the row could not tell an answer RESUMED from a round STARTED OVER. " +
                "Absent on a turn whose every continuation carried a partial, which is the common case.",
        ),
        entry(
            PerfKeys.STALL_MS,
            "ms",
            "V4-116: the upstream SILENCE that triggered each re-anchor, summed in ms ; the " +
                "watchdog's own `idleMs` at the moment it fired, so the row answers \"silent how long\" " +
                "without archaeology through the journal. Paired with [REANCHORS] on purpose: neither " +
                "number is interpretable alone (one POST after 9 minutes of silence and five POSTs " +
                "after 20 seconds each are opposite diagnoses), and a stall judged by the mid-output " +
                "stall tier reports a value at that tier, not the 300s streamIdle an operator would " +
                "otherwise assume. A SUM like every other duration counter here ([BACKOFF_MS], " +
                "[REFRESH_MS]); a turn with two stalls reports both.",
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

private const val BYTES = "bytes"

private const val TOKENS = "tokens"
