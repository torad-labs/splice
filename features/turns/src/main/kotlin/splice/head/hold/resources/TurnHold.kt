// The hold at the head: the wait and the resources around it (failure-handling slice 8).
package splice.head.hold.resources

import splice.core.failure.FailureCause
import splice.core.failure.Recovery
import splice.core.failure.WaitLine
import splice.core.memory.HeapOwnerState
import splice.core.util.WallClock
import splice.head.hold.HeldTurn
import splice.head.turn.TurnState
import splice.upstream.retry.HoldAsk
import splice.upstream.retry.HoldEnd
import splice.upstream.retry.Probes
import java.time.Instant

/** One turn's hold at its head: the [held] place among its login's turns, its [surfaces], and its [wall]. */
internal class TurnHold(
    private val held: HeldTurn,
    private val surfaces: HoldSurfaces,
    private val wall: SessionWall,
    private val wallClock: WallClock,
    /** The size of the request the turn keeps in memory while it waits. */
    private val heapBytes: KeptBytes,
) {
    fun wallLeftMs(): Long = wall.holdLeftMs()

    suspend fun hold(ask: HoldAsk): HoldEnd {
        val soft = ask.probes == Probes.SOFT_LIFT
        surfaces.terminal.recovering(WaitLine(ask.cause, Recovery.Hold(ask.until, soft)))
        surfaces.terminal.holding(true)
        val now = wallClock()
        val left = wall.holdLeftMs()
        val wallEndMs = if (left == Long.MAX_VALUE) Long.MAX_VALUE else now + left
        surfaces.slot.yieldPermit()
        surfaces.watchdog.holdStarted()
        surfaces.liveTurns.state(surfaces.slot, heldState(ask))
        surfaces.lease?.state(HeapOwnerState.QUEUED)
        val end = try {
            held.hold(ask, wallEndMs, surfaces.lost)
        } finally {
            surfaces.lease?.state(HeapOwnerState.ACTIVE)
            surfaces.terminal.holding(false)
            surfaces.liveTurns.state(surfaces.slot, TurnState.Running)
            surfaces.watchdog.holdEnded()
        }
        if (end == HoldEnd.RESUME || end == HoldEnd.LOGIN_JOINED) surfaces.slot.retakePermit()
        return end
    }

    private fun heldState(ask: HoldAsk): TurnState.Held = TurnState.Held(
        until = ask.until,
        cause = when (ask.probes) {
            Probes.CREDIT -> "credit"
            Probes.FIRM, Probes.SOFT_LIFT, Probes.UNSTATED, Probes.NONE ->
                if (ask.cause is FailureCause.Provider.Overloaded) "overload" else "limit"
        },
        login = ask.login,
        since = Instant.ofEpochMilli(ask.heldSinceMs),
        heapBytes = heapBytes(),
    )
}
