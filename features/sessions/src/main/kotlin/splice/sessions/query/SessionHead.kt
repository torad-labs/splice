// NEW: LAYOUT-01 — the head facts session queries consume. Control adapts its wider ManagedHead
// into this projection, so the sessions feature never depends upward on daemon/control.
package splice.sessions.query

import splice.core.model.ModelCatalog
import java.nio.file.Path

/** One head as session, project, and team projections see it. */
public data class SessionHead(
    /** The head's isolated Claude Code config root, when it has one. */
    val transcriptRoot: Path? = null,
    /** Windowed turn rows used by project and team economics. */
    val perfRows: SessionPerfSource? = null,
    /** The rate cards used to price this head's rows. */
    val catalog: ModelCatalog? = null,
    /** Whether a plan covers an account's requests, so a plan's requests are never counted as a key's cost. */
    val plans: SessionAccountPlans = SessionAccountPlans { false },
)

/** Whether a plan covers the requests an account carried: the plan question, asked of the head's accounts. */
public fun interface SessionAccountPlans {
    public operator fun invoke(account: String?): Boolean
}

/** One turn row narrowed to the fields session projections consume. */
public data class SessionPerfRow(
    val ts: Long,
    val outcome: String,
    val fields: Map<String, Long>,
    val model: String? = null,
    val session: String? = null,
    /** The account label that carried the request. */
    val account: String? = null,
)

/** One coherent read of retained turn rows. */
public data class SessionPerfWindow(
    val rows: List<SessionPerfRow>,
    val oldestHeldTs: Long? = null,
)

/** Reads retained turn rows at or after [sinceMs]. */
public fun interface SessionPerfSource {
    public fun window(sinceMs: Long): SessionPerfWindow
}
