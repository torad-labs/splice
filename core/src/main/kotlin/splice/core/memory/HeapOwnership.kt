// NEW: closed telemetry vocabulary; owner labels never contain request or credential content.
package splice.core.memory

import kotlinx.serialization.Serializable

/** A reservation's one accounting category, shared by every handle to that allocation. */
@Serializable
public enum class HeapCategory {
    UNCLASSIFIED,
    CONNECTION,
    INGRESS,
    MATERIALIZATION,
    TRACE,
    DECODED_BODY,
    RECENT_HISTORY,
    TRANSCRIPT_CACHE,
    CONFIG_SNAPSHOT,
    AUTH_EVIDENCE,
    REPLAY,
    CODE_MODE,
}

/** Logical handle ownership, not a claim about JVM reachability or collected object bytes. */
@Serializable
public enum class HeapOwnerState {
    ACTIVE,
    QUEUED,
    RUNNING,
    AWAITING_COLLECTION,
}

/** Eviction is reported only by an actual eviction hook; Cleaner reports observation separately. */
public enum class HeapRelease {
    CLOSED,
    EVICTED,
    COLLECTED,
}
