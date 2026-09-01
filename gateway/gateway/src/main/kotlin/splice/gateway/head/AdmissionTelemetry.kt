// NEW: the admission plane's perf stamps (HD-24) — the row a turn opens with and the three counts
// it earns the moment a gate slot is in hand. Its own file because this is the only place the
// admission path touches splice.core.perf and splice.core.util, and the ORDER is the contract:
// the row is opened before the admission clock reading, and the stamps land immediately after the
// slot is returned, so INFLIGHT is the depth the turn actually entered at.
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.spi.InflightGate

internal class AdmissionTelemetry(
    private val gate: InflightGate,
    private val clock: ElapsedClock,
) {
    fun begin(): TurnPerf = TurnPerf(clock)

    fun markAdmitted(perf: TurnPerf) {
        perf.mark(PerfKeys.GATE)
        perf.setCount(PerfKeys.INFLIGHT, gate.snapshot().inflight.toLong())
        perf.setCount(PerfKeys.ASYNC_IO_DROPS, AsyncFileIo.droppedCount().toLong())
    }
}
