// NEW: a reserved forwarding-place label is not proof of a historical account identity.
package splice.app.control

import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow

/** Normalize before filtering and aggregation so unattributed Requests links cover exactly the same rows. */
internal class NativeAccountRows(private val source: PerfRowsSource) : PerfRowsSource {
    override fun window(sinceMs: Long): PerfRowsWindow {
        val read = source.window(sinceMs)
        return read.copy(
            rows = read.rows.map { row ->
                if (row.account == OWN_SIGN_IN_LABEL) row.copy(account = null) else row
            },
        )
    }
}
