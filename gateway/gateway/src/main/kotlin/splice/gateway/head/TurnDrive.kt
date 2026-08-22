// PORT-OF: splice/gateway/head/TurnDriver.kt (TurnDrive) @ 86f1411 — invariants
// unchanged: the per-turn collaborators + data the drive needs. Split out (HD-24) because
// TurnDrive is already a package-wide type (WsRoundDriver + DrivePorts read it) — a package-wide
// type should not live inside one consumer. TurnInputs lives in TurnInputs.kt.
package splice.gateway.head

import kotlinx.serialization.json.JsonObject
import splice.core.perf.TurnPerf
import splice.core.turn.TurnMeta
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.round.RunnerSignals
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.TurnTerminal
import splice.spi.InflightGate
import splice.spi.ToolSearchController
import splice.spi.TurnWatchdog

/** The per-turn collaborators + data the drive needs, grouped so the drive signature stays one
 *  cohesive argument (they are all created together per request inside the SSE writer). */
internal data class TurnDrive(
    val bodyJson: String,
    /** The same request as [bodyJson], kept typed so reasoning-continuation folding can extend its
     *  `input` and re-POST without re-parsing (non-fold turns never read it). */
    val requestBody: JsonObject,
    val meta: TurnMeta,
    val emitter: TurnTerminal,
    val watchdog: TurnWatchdog,
    val slot: InflightGate.Slot,
    val pipeline: TurnPipeline,
    val t0: Long,
    val upstreamModel: String,
    val perf: TurnPerf,
    /** Per-turn upstream headers from BuiltTurn (e.g. grok conv-id affinity). */
    val turnHeaders: Map<String, String>,
    /** Runner liveness gates + the health hook for absorbed round failures (built once in
     *  TurnDriveFactory.assembleDrive; one construction site, the policies never drift). */
    val signals: RunnerSignals,
    /** The client-facing SSE channel: coalesced writer + write mutex + clientGone flag. */
    val channel: ClientChannel,
    /** The provider's answering policy for THIS turn's deferred tool surface. Null = no deferral
     *  this turn, or the feature is off — the round loop is byte-for-byte unchanged. */
    val toolSearch: ToolSearchController?,
) {
    // `internal`, not `private`: TurnDrive is an internal type and TurnDriver (a different class)
    // reads this — a private member would be unreachable. Reads only this drive's own `perf`.
    internal fun perfCounter(key: String): Long = perf.snapshot().counters[key] ?: 0L
}
