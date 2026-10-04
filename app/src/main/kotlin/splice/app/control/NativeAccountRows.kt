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
        return read.copy(rows = read.rows.map(::normalized))
    }

    override fun <T> projected(sinceMs: Long, read: PerfProjectionRead<T>): T {
        val projected = source as? ProjectedPerfRowsSource
        if (projected != null) {
            return projected.projected(sinceMs) { projection -> read(normalizedProjection(projection)) }
        }
        val window = window(sinceMs)
        return read(object : PerfRowsProjection {
            override val window: PerfRowsWindow = window
            override fun complete(rows: List<PerfRow>): List<PerfRow> = rows
        })
    }

    private fun normalizedProjection(projection: PerfRowsProjection): PerfRowsProjection {
        val originals = IdentityHashMap<PerfRow, PerfRow>()
        val rows = projection.window.rows.map { row ->
            val normal = normalized(row)
            if (normal !== row) originals[normal] = row
            normal
        }
        return object : PerfRowsProjection {
            override val window: PerfRowsWindow = projection.window.copy(rows = rows)
            override fun complete(rows: List<PerfRow>): List<PerfRow> =
                projection.complete(rows.map { originals[it] ?: it }).map(::normalized)
        }
    }

    private fun normalized(row: PerfRow): PerfRow =
        if (row.account == OWN_SIGN_IN_LABEL) row.copy(account = null) else row
}
