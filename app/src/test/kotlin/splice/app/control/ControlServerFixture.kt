// NEW: the one place the control-server tests build a ControlServer and its /health body. Forty-odd rigs construct
// it, and the server's composition is changing (ControlServer takes its mounts instead of building them), so the
// construction lives here and each rig names only what it exercises. A rig that sets a silent runtime or a turn-path
// stall passes the same [HeadSignals] to both the health body and the server: the heads route and /health read them.
package splice.app.control

import splice.app.control.api.ControlAudit
import splice.app.control.api.ControlHealthReport
import splice.app.control.api.HeadReadiness
import splice.app.control.api.HeadSignals
import splice.app.control.mount.ControlGuard
import splice.configuration.topology.TopologyStale
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker
import splice.head.admission.AdmissionErrorBody
import splice.http.ingress.HeapIngress
import splice.upstream.memory.JvmHeap

internal fun signalsFor(
    heads: Map<String, ManagedHead>,
    runtimeNotAnswering: RuntimeNotAnswering = RuntimeNotAnswering { emptyMap() },
): HeadSignals = HeadSignals(heads, runtimeNotAnswering)

/** The /health body a rig serves. [signals] is the one the server's heads route reads too, so a rig passes the
 *  same value to [controlServerFor] and to here whenever the two must agree. */
internal fun healthFor(
    heads: Map<String, ManagedHead>,
    failedHeads: FailedHeads = FailedHeads { 0 },
    configuredHeads: Int = heads.size,
    turnPathStalled: TurnPathStalled = TurnPathStalled { emptyList() },
    topologyDigest: TopologyDigest = TopologyDigest { "" },
    configPath: String = "",
    topologyStale: TopologyStale = TopologyStale { false },
    signals: HeadSignals = signalsFor(heads),
    clientVersions: ClientVersionTracker = ClientVersionTracker(),
    bootedAtEpochMillis: Long = System.currentTimeMillis(),
): ControlHealthReport = ControlHealthReport(
    readiness = HeadReadiness(heads, failedHeads, configuredHeads, turnPathStalled),
    signals = signals,
    topologyDigest = topologyDigest,
    configPath = configPath,
    topologyStale = topologyStale,
    clientVersions = clientVersions,
    bootedAtEpochMillis = bootedAtEpochMillis,
)

internal fun controlServerFor(
    port: Int,
    heads: Map<String, ManagedHead>,
    config: ConfigService,
    mgmtKey: MgmtKey,
    log: LogSink,
    signals: HeadSignals = signalsFor(heads),
    runtime: ControlRuntime = ControlRuntime(),
    // The health body and the server read one client-version tracker, as ControlPlane wires them.
    health: ControlHealthReport = healthFor(heads, signals = signals, clientVersions = runtime.clientVersions),
): ControlServer {
    // The same guard ControlPlane builds: the production request cap, read from its one knob.
    val guard = ControlGuard(
        mgmtKey,
        ControlAudit(log),
        log,
        HeapIngress(JvmHeap.budget, Knob.MAX_REQUEST_BYTES.count(), AdmissionErrorBody),
    )
    return ControlServer(
        port,
        heads,
        config,
        guard,
        log,
        health = health,
        signals = signals,
        runtime = runtime,
    )
}
