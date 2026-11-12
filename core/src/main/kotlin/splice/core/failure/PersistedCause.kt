// NEW: failure-handling slice 1 (2026-10-26). The names a failure's perf row stores, moved here from the enum in
// core/turn/FailureCause.kt so stored rows and the usage console keep reading the same strings.
package splice.core.failure

/** The perf row's cause names. Today's 19 are kept as written, so stored rows and the usage console read the same
 *  strings; [CONNECT_REFUSED] is the name the conn-reset ending already wrote for a refused local runtime port. */
public enum class PersistedCause {
    UPSTREAM_STALLED,
    UPSTREAM_TRUNCATED,
    UPSTREAM_CONN_RESET,
    UPSTREAM_STATUS_5XX,
    UPSTREAM_STATUS_4XX,
    UPSTREAM_REPORTED,
    MODEL_REFUSED,
    CONTENT_FILTERED,
    TOOL_TEAR,
    DIALECT_UNSUPPORTED,
    CODE_MODE_PROTOCOL,
    INTERNAL,
    VENDOR_RATE_LIMITED,
    VENDOR_QUOTA_EXHAUSTED,
    AUTH_MISSING,
    AUTH_REFRESH_FAILED,

    /** Readable in old rows only: a turn that met every login at its limit now writes its own Limited. */
    POOL_EXHAUSTED,
    ADMISSION_FULL,
    REQUEST_TOO_LARGE,
    CONNECT_REFUSED,
    HOLD_OUTLASTED,
    COMPACTION_UNFINISHED,
    DECLINED,
    STOPPED,
}
