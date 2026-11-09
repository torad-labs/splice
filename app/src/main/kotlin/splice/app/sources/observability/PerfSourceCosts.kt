package splice.app.sources.observability

import kotlinx.serialization.Serializable
import splice.core.util.PerfWorkBudget

/** Scalar probes only: query diagnostics never feed the observer or trigger an export. */
public class PerfSourceCosts {
    private var active = 0L
    private var queries = 0L
    private var cold = 0L
    private var rows = 0L
    private var bytes = 0L
    private var elapsed = 0L
    private var busy = 0L
    private var warmProjections = 0L
    private var coldProjections = 0L
    private var historyBytes = 0L

    @Synchronized
    internal fun begin(isCold: Boolean) {
        active++
        queries++
        if (isCold) cold++
    }

    @Synchronized
    internal fun end(scannedRows: Long, scannedBytes: Long, elapsedMs: Long) {
        active--
        rows += scannedRows
        bytes += scannedBytes
        elapsed += elapsedMs
    }

    @Synchronized
    internal fun refused() { busy++ }

    @Synchronized
    internal fun projection(warm: Boolean) {
        if (warm) warmProjections++ else coldProjections++
    }

    @Synchronized
    internal fun disk(size: Long) { historyBytes = size }

    @Synchronized
    public fun snapshot(): PerfSourceCostSnapshot = PerfSourceCostSnapshot(
        active, queries, cold, rows, bytes, elapsed, busy, warmProjections, coldProjections,
        historyBytes, PerfWorkBudget.retainedBytes(),
    )
}

@Serializable
public data class PerfSourceCostSnapshot(
    val activeQueries: Long,
    val queries: Long,
    val cacheMisses: Long,
    val rowsScanned: Long,
    val bytesScanned: Long,
    val queryMs: Long,
    val busy: Long,
    val warmProjections: Long,
    val coldProjections: Long,
    val historyBytes: Long,
    val retainedBytes: Long,
)
