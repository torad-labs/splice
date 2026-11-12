// NEW: LAYOUT-01 — control-owned adapters from ManagedHead into the narrow sessions query contract.
package splice.app.control

import splice.core.config.UserHome
import splice.core.retired.RetiredHeads
import splice.sessions.query.SessionAccountPlans
import splice.sessions.query.SessionHead
import splice.sessions.query.SessionPerfRow
import splice.sessions.query.SessionPerfSource
import splice.sessions.query.SessionPerfWindow
import splice.sessions.transcript.SessionHistoryRoot
import java.nio.file.Path

internal object SessionHeadAdapter {
    fun adapt(heads: Map<String, ManagedHead>): Map<String, SessionHead> =
        heads.mapValues { (_, head) -> adapt(head) }

    /** Native, configured and retired transcript roots, kept in their existing discovery order. */
    fun historyRoots(heads: Map<String, SessionHead>, stateDir: Path): List<SessionHistoryRoot> =
        listOf(SessionHistoryRoot(null, UserHome.claudeDir())) +
            heads.mapNotNull { (head, source) -> source.transcriptRoot?.let { SessionHistoryRoot(head, it) } } +
            // A removed command's sessions stay readable: its config directory is on the retired record.
            RetiredHeads(stateDir).all().mapNotNull { retired ->
                retired.configDir?.let { SessionHistoryRoot(retired.key, Path.of(it)) }
            }

    private fun adapt(head: ManagedHead): SessionHead = SessionHead(
        transcriptRoot = head.launchSpec?.trees?.own,
        perfRows = head.sources.perfRows?.let { source ->
            SessionPerfSource { sinceMs ->
                val window = source.window(sinceMs)
                SessionPerfWindow(
                    rows = window.rows.map { row ->
                        SessionPerfRow(
                            ts = row.ts,
                            outcome = row.outcome,
                            fields = row.fields,
                            model = row.facts.model,
                            session = row.facts.session,
                            account = row.facts.account,
                        )
                    },
                    oldestHeldTs = window.oldestHeldTs,
                )
            }
        },
        catalog = head.statusline.catalog,
        plans = SessionAccountPlans { head.authSurface.plans(it) },
    )
}
