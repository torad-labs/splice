// PORT-OF: splice/gateway/head/TurnDrive.kt (TurnInputs) @ 86f1411 — invariants unchanged:
// admission-time inputs threaded into a drive. Own file (concentration, 2026-08-19) so TurnDrive
// is not billed for a second column-0 type.
package splice.gateway.head

import splice.core.perf.TurnPerf
import splice.spi.BuiltTurn
import splice.spi.InflightGate

/** Admission-time inputs threaded into a drive — grouped so the drive assembler stays one
 *  cohesive argument across the stream and collect entries. */
internal data class TurnInputs(
    val built: BuiltTurn,
    val slot: InflightGate.Slot,
    val t0: Long,
    val perf: TurnPerf,
)
