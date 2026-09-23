// NEW: LAYOUT-01 — control-owned adapters from ManagedHead into the narrow sessions query contract.
package splice.control

import splice.sessions.query.SessionHead
import splice.sessions.query.SessionPerfRow
import splice.sessions.query.SessionPerfSource
import splice.sessions.query.SessionPerfWindow

internal object SessionHeadAdapter {
    fun adapt(heads: Map<String, ManagedHead>): Map<String, SessionHead> =
        heads.mapValues { (_, head) -> adapt(head) }

    private fun adapt(head: ManagedHead): SessionHead = SessionHead(
        transcriptRoot = head.launchSpec?.trees?.own,
        perfRows = head.perfRows?.let { source ->
            SessionPerfSource { sinceMs ->
                val window = source.window(sinceMs)
                SessionPerfWindow(
                    rows = window.rows.map { row ->
                        SessionPerfRow(
                            ts = row.ts,
                            outcome = row.outcome,
                            fields = row.fields,
                            model = row.model,
                            session = row.session,
                        )
                    },
                    oldestHeldTs = window.oldestHeldTs,
                )
            }
        },
        catalog = head.catalog,
    )
}
