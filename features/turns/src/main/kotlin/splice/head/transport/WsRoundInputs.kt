// NEW: the per-round collaborators the WS drive needs, grouped so the
// entry point stays one cohesive argument (concentration, 2026-08-19).
package splice.head.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.perf.PerfKeys
import splice.head.turn.TurnDrive
import splice.upstream.ClientFrameEmitted
import splice.upstream.RoundBody
import splice.upstream.sse.IndependentRoundSink
import splice.upstream.sse.WireSink

internal data class WsRoundInputs(
    val drive: TurnDrive,
    val body: RoundBody,
    val sink: WireSink,
    val scope: CoroutineScope,
    val turnJob: Job,
    val frameEmittedThisRound: ClientFrameEmitted,
    val eventsBase: Long,
) {
    // The turn's count when this round's inputs are made, before its WebSocket attempt. Never copied: a copy
    // would take the count again, after the attempt.
    private val sizeRefusalsBefore = drive.perfCounter(PerfKeys.WS_REFUSED_TOO_LARGE)

    /** This round's WebSocket peer refused its body as too large (a 1009 before any event), so the same body's
     *  HTTP 4xx is that refusal again (WsSizeRefusal). */
    fun refusedAsTooLarge(): Boolean = drive.perfCounter(PerfKeys.WS_REFUSED_TOO_LARGE) > sizeRefusalsBefore

    /** The source reader has no first-client dependency. Real cancellation still aborts its job/body. */
    fun clientGone(): Boolean = sink !is IndependentRoundSink && drive.channel.clientGone.get()
}
