// NEW: the per-round collaborators the WS drive needs, grouped so the
// entry point stays one cohesive argument (concentration, 2026-08-19).
package splice.gateway.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.upstream.ClientFrameEmitted
import splice.upstream.sse.WireSink

internal data class WsRoundInputs(
    val drive: TurnDrive,
    val bodyJson: String,
    val sink: WireSink,
    val scope: CoroutineScope,
    val turnJob: Job,
    val frameEmittedThisRound: ClientFrameEmitted,
    val eventsBase: Long,
)
