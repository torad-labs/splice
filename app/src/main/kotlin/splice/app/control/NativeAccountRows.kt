// NEW: a reserved forwarding-place label is not proof of a historical account identity.
package splice.app.control

import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.usage.perf.PerfProjectionRead
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsProjection
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow
import splice.usage.perf.ProjectedPerfRowsSource
import java.util.IdentityHashMap

/** Normalize before filtering and aggregation so unattributed Requests links cover exactly the same rows. */
internal class NativeAccountRows(private val source: PerfRowsSource) : PerfRowsSource, ProjectedPerfRowsSource {
    override fun window(sinceMs: Long): PerfRowsWindow {
        val read = source.window(sinceMs)
        return read.copy(
            rows = read.rows.map { row ->
                if (row.account == OWN_SIGN_IN_LABEL) row.copy(account = null) else row
            },
        )
    }

    override fun <T> projected(sinceMs: Long, read: PerfProjectionRead<T>): T {
        if (source is ProjectedPerfRowsSource) {
            return source.projected(sinceMs) { projection -> read(normalized(projection)) }
        }
        val held = window(sinceMs)
        return read(
            object : PerfRowsProjection {
                override val window: PerfRowsWindow = held
                override fun complete(rows: List<PerfRow>): List<PerfRow> = rows
            },
        )
    }

    private fun normalized(projection: PerfRowsProjection): PerfRowsProjection {
        val originals = IdentityHashMap<PerfRow, PerfRow>()
        val held = projection.window
        val rows = held.rows.map { row ->
            if (row.account == OWN_SIGN_IN_LABEL) {
                row.copy(account = null).also { originals[it] = row }
            } else {
                row
            }
        }
        return object : PerfRowsProjection {
            override val window: PerfRowsWindow = held.copy(rows = rows)
            override fun complete(rows: List<PerfRow>): List<PerfRow> =
                projection.complete(rows.map { originals[it] ?: it }).map { row ->
                    if (row.account == OWN_SIGN_IN_LABEL) row.copy(account = null) else row
                }
        }
    }
}
