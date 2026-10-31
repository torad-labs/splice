// NEW: knob kinds plus the derived lookup tables. Split from Knob.kt so the
// enum catalogue is not billed for the kind + indexes (concentration, 2026-08-19).
package splice.core.config

public enum class KnobKind { STRING, NUMBER, BOOL }

// Companion dissolved (Kotlin style law, 2026-08-16 — HD-M8). Both are derived TABLES, not
// functions, so the law's sanctioned home is file scope; they are RENAMED on the way out because a
// package-scope `byKey` / `restartRequiredKeys` says nothing about what it indexes, and consumers
// import them by name. Computed once at class-init exactly as the companion's vals were.
// FILE SCOPE ON PURPOSE: one map and one list per process, never per Knob read.
public val knobsByKey: Map<String, Knob> = Knob.entries.associateBy { it.key }
public val restartRequiredKnobKeys: List<String> = Knob.entries.filter { it.restartRequired }.map { it.key }

/** Knobs only [heads.KEY.overrides] may set (Knob.headOnly); every global layer drops them. */
internal val headOnlyKnobKeys: Set<String> = Knob.entries.filter { it.headOnly }.map { it.key }.toSet()

/** Knobs only the daemon-wide value reaches: every reader asks the global view, never a head's own, so a
 *  [heads.KEY.overrides] value for one of these would be accepted and then ignored. The per-command write refuses them
 *  by name (HeadOverrideWrite). Each was traced to its reader on Oct 12 (§9 audit). Live: ingressReadIdleMs and
 *  materializationHeapBytes (the shared ingress and materialization gate), the four MCP host limits (one host for every
 *  head), transcriptView (console routes), budgetDefaultAction (budget PUT), historyRetentionDays (the one history
 *  window), activityRetentionDays, messageEdges, keepTranscriptCopies and transcriptCopyDays (daemon-wide stores and
 *  moves). Read at start:
 *  debug, controlPort, supervisorUnit, mcpSlice and activityStoreHeads. A knob that gains a per-head reader leaves it.
 *
 *  The history window is the one exception to "no head reads it": historyRetentionDays, and perfArchiveRetentionDays,
 *  the spelling it replaced, also feed a head's trace days (SpliceConfig.traceKeptDays, read per head). A command's own
 *  window would reach only that command's saved prompts and split them from the one window every history reader
 *  keeps (Marlin, Oct 10), so both are refused per command too. Every other restart-only knob outside this list was
 *  traced to a per-head reader on Oct 12 and stays settable per command. */
public val daemonWideKnobs: Set<Knob> = setOf(
    Knob.UPSTREAM_TIMEOUT_MS,
    Knob.FIRST_BYTE_TIMEOUT_MS,
    Knob.STREAM_IDLE_MS,
    Knob.STALL_REANCHOR_MS,
    Knob.INGRESS_READ_IDLE_MS,
    Knob.MATERIALIZATION_HEAP_BYTES,
    Knob.MCP_IDLE_TIMEOUT_MS,
    Knob.MCP_MAX_SERVERS,
    Knob.MCP_REQUEST_TIMEOUT_MS,
    Knob.MCP_INITIALIZE_TIMEOUT_MS,
    Knob.TRANSCRIPT_VIEW,
    Knob.BUDGET_DEFAULT_ACTION,
    Knob.HISTORY_RETENTION_DAYS,
    Knob.PERF_ARCHIVE_RETENTION_DAYS,
    Knob.ACTIVITY_RETENTION_DAYS,
    Knob.MESSAGE_EDGES,
    Knob.KEEP_TRANSCRIPT_COPIES,
    Knob.TRANSCRIPT_COPY_DAYS,
    Knob.DEBUG,
    Knob.CONTROL_PORT,
    Knob.SUPERVISOR_UNIT,
    Knob.MCP_SLICE,
    Knob.ACTIVITY_STORE_HEADS,
)

/** Per-head values for these knobs cannot create a different timing or daemon policy. */
internal const val DAEMON_WIDE_OVERRIDE: String = "is daemon-wide; set it under [defaults] instead"
