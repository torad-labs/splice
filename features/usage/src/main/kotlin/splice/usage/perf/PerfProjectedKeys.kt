// NEW: Oct 11, 2026 — the numeric perf fields a projected read keeps for EVERY row of its window.
package splice.usage.perf

import splice.core.perf.PerfKeys

/** The numeric fields the turns route reads over a whole window: its filters and row tests ([TurnsFilter],
 *  [TurnsTest]), the usage fold and price classification ([TurnUsage], [TurnPriceGap], [splice.core.model.TurnBill]) and
 *  the most-at-once peak ([PerfPeak]). A source that retains a window compactly keeps these and nothing else, and
 *  hands the rows the answer shows whole through [PerfRowsProjection.complete], so a figure read from a window row
 *  must read a key named here. `ts` rides with them because the row header requires it. */
public object PerfProjectedKeys {
    public val numeric: List<String> = listOf(
        "ts",
        PerfKeys.TOTAL,
        PerfKeys.FIRST_BYTE,
        PerfKeys.FIRST_DELTA,
        PerfKeys.STALL_MS,
        PerfKeys.UP_GAP_MAX_MS,
        PerfKeys.UP_GAP_MAX_START_EPOCH_MS,
        PerfKeys.ADMIT_WAIT_MS,
        PerfKeys.ADMIT_HELD_MS,
        PerfKeys.INFLIGHT,
        PerfKeys.REANCHORS,
        PerfKeys.REANCHORS_FROM_SCRATCH,
        PerfKeys.LOCAL_STEP,
        PerfKeys.COMPARE,
        PerfKeys.ATTEMPTS,
        PerfKeys.TRANSPORT_ATTEMPT_STARTS,
        PerfKeys.EVENTS_IN,
        PerfKeys.CONTENT_FRAMES_OUT,
        PerfKeys.UPSTREAM_REQ_BYTES,
        PerfKeys.NO_REQUEST,
        PerfKeys.IN_TOKENS,
        PerfKeys.OUT_TOKENS,
        PerfKeys.CACHED_TOKENS,
        PerfKeys.CACHE_WRITE_TOKENS,
        PerfKeys.CACHE_WRITE_1H_TOKENS,
        PerfKeys.CUT_SOURCE_ROUNDS,
        PerfKeys.ABSORBED_ROUNDS,
        PerfKeys.ABSORBED_IN_TOKENS,
        PerfKeys.ABSORBED_CACHED_TOKENS,
        PerfKeys.ABSORBED_CACHE_WRITE_TOKENS,
        PerfKeys.ABSORBED_CACHE_WRITE_1H_TOKENS,
        PerfKeys.ABSORBED_OUT_TOKENS,
        // The summary folds the same window (PerfSummary): its retries, refreshes, stream ends, dropped writes and
        // activity side queries, so it reads the compact window beside the turns route instead of the full-row tier.
        PerfKeys.RETRIES,
        PerfKeys.REFRESHES,
        PerfKeys.STREAM_END,
        PerfKeys.ASYNC_IO_DROPS,
        PerfKeys.ACTIVITY_QUERY,
        // The hourly totals read the same compact window (PerfRowsFileSource.economicsEvidence), so the startup scan
        // decodes each line once for both: the hour's request bytes and tool partition are the figures they add.
        PerfKeys.REQ_BYTES,
        PerfKeys.TOOLS_EAGER,
        PerfKeys.TOOLS_DEFERRED,
    )
}
