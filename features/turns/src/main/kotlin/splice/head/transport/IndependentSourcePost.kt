// NEW: the raw reader owns admission, request heap, stop and cap until actual upstream completion.
package splice.head.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import splice.core.turn.TurnOutcome
import splice.head.turn.TurnDrive
import splice.head.turn.TurnUsageStamp
import splice.upstream.sse.IndependentRoundSink
import splice.upstream.sse.WireSink

internal class IndependentSourcePost(
    private val driver: SseRoundDriver,
    private val usageStamp: TurnUsageStamp,
) {
    suspend fun post(
        drive: TurnDrive,
        body: String,
        sink: WireSink,
        clientScope: CoroutineScope,
        clientJob: Job,
    ): TurnOutcome {
        val independent = sink as? IndependentRoundSink
        val owner = independent?.ownerScope ?: clientScope
        val job = owner.coroutineContext[Job] ?: clientJob
        val lease = independent?.let { drive.slot.retainSource(drive.meta.sessionId) }
        val cap = independent?.let { drive.watchdog.launchTotalCap(owner, job) }
        if (independent != null) drive.sourceRoundStarted?.started(job)
        return try {
            val outcome = driver.postRound(drive, body, sink, owner, job)
            if (independent == null) outcome else usageStamp.stampIndependent(outcome)
        } finally {
            cap?.cancel()
            lease?.release()
        }
    }
}
