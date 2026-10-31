// The resources a held turn yields and restores (failure-handling slice 8).
package splice.head.hold.resources

import kotlinx.coroutines.Deferred
import splice.core.memory.HeapLease
import splice.head.turn.LiveTurns
import splice.head.wire.TurnTerminal
import splice.upstream.retry.InflightGate
import splice.upstream.retry.TurnWatchdog

/** What a hold touches besides its wait: the turn's line, its gate slot, its no-progress cap, its row in the live
 *  list and its heap lease. */
internal data class HoldSurfaces(
    val terminal: TurnTerminal,
    val slot: InflightGate.Slot,
    val watchdog: TurnWatchdog,
    val liveTurns: LiveTurns,
    /** Completes when the client of a turn that outlives it (a compaction) leaves. */
    val lost: Deferred<Unit>,
    /** The heap lease the turn's request is charged to: queued while it waits, active again when it goes on. */
    val lease: HeapLease?,
)
