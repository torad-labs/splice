// PORT-OF: splice/gateway/head/TurnDriver.kt (assembleDrive) @ 86f1411 — invariants unchanged: the
// construction site of the per-turn drive — perf stamping, TurnWatchdog, RunnerSignals,
// TurnPipeline, output clamp. Split out (HD-24) so it can take [deps] whole (dropping
// splice.core.util and splice.gateway.usage from TurnDriver, whose last named references left with
// it) instead of the loose ElapsedClock/LogSink getters TurnDriver used to forward. [health] is a
// separate constructor param, not read off HeadDeps: it is the SAME HeadHealthCounters instance
// TurnDriver owns across every turn (cumulative counting), not a per-turn dependency.
package splice.gateway.head

import splice.core.perf.PerfKeys
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.TurnTerminal
import splice.spi.Provider
import splice.spi.TurnWatchdog

internal class TurnDriveFactory(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val health: HeadHealthCounters,
) {
    private val driveSignals = DriveSignals(provider, deps, health)
    private val drivePipeline = DrivePipeline(provider, deps)
    /** Assemble the per-turn drive around a terminal (SseEmitter for stream, CollectingTerminal for
     *  collect) and its channel — everything else (watchdog, pipeline, headers) is shape-neutral. */
    fun assembleDrive(
        inputs: TurnInputs,
        emitter: TurnTerminal,
        channel: ClientChannel,
    ): TurnDrive {
        val built = inputs.built
        val perf = inputs.perf
        val meta = built.meta
        val bodyJson = built.requestBody.toString()
        perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, bodyJson.length.toLong())
        // Tool-surface partition sizes — the expected-delta instrument (#959): setCount (not add)
        // so a request that stamped tools_deferred=0 is VISIBLE, never silently absent.
        meta.toolsEager?.let { perf.setCount(PerfKeys.TOOLS_EAGER, it.toLong()) }
        meta.toolsDeferred?.let { perf.setCount(PerfKeys.TOOLS_DEFERRED, it.toLong()) }
        val watchdog = TurnWatchdog(provider.watchdog, deps.clock)
        val signals = driveSignals.make(watchdog, channel, perf)
        return TurnDrive(
            bodyJson = bodyJson,
            requestBody = built.requestBody,
            meta = meta,
            emitter = emitter,
            watchdog = watchdog,
            slot = inputs.slot,
            pipeline = drivePipeline.make(meta),
            t0 = inputs.t0,
            upstreamModel = meta.upstreamModel,
            perf = perf,
            turnHeaders = built.extraHeaders,
            channel = channel,
            signals = signals,
            toolSearch = built.toolSearch,
        )
    }
}
