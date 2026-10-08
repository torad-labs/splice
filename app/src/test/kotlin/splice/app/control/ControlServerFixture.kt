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

/** The readiness a rig reports: the heads it serves, how many failed, how many were configured, which are stalled. */
internal fun readinessFor(
    heads: Map<String, ManagedHead>,
    failedHeads: FailedHeads = FailedHeads { 0 },
    configuredHeads: Int = heads.size,
    turnPathStalled: TurnPathStalled = TurnPathStalled { emptyList() },
): HeadReadiness = HeadReadiness(heads, failedHeads, configuredHeads, turnPathStalled)

/** The /health body a rig serves. [signals] is the one the server's heads route reads too, so a rig passes the
 *  same value to [controlServerFor] and to here whenever the two must agree. */
internal fun healthFor(
    heads: Map<String, ManagedHead>,
    readiness: HeadReadiness = readinessFor(heads),
    signals: HeadSignals = signalsFor(heads),
    clientVersions: ClientVersionTracker = ClientVersionTracker(),
    bootedAtEpochMillis: Long = System.currentTimeMillis(),
): ControlHealthReport = ControlHealthReport(
    readiness = readiness,
    signals = signals,
    topologyDigest = TopologyDigest { "" },
    configPath = "",
    topologyStale = TopologyStale { false },
    clientVersions = clientVersions,
    bootedAtEpochMillis = bootedAtEpochMillis,
)

/** The /health body of a rig whose running topology is [topologyDigest] at [configPath] and reads [topologyStale]. */
internal fun topologyHealthFor(
    heads: Map<String, ManagedHead>,
    topologyDigest: TopologyDigest = TopologyDigest { "" },
    configPath: String = "",
    topologyStale: TopologyStale = TopologyStale { false },
    readiness: HeadReadiness = readinessFor(heads),
): ControlHealthReport = ControlHealthReport(
    readiness = readiness,
    signals = signalsFor(heads),
    topologyDigest = topologyDigest,
    configPath = configPath,
    topologyStale = topologyStale,
    clientVersions = ClientVersionTracker(),
    bootedAtEpochMillis = System.currentTimeMillis(),
)

/** What guards a rig's control server: the management key it accepts and the log its audit lines go to. */
internal class ControlAuth(private val mgmtKey: MgmtKey, val log: LogSink) {
    /** The same guard ControlPlane builds: the production request cap, read from its one knob. */
    fun guard(): ControlGuard = ControlGuard(
        mgmtKey,
        ControlAudit(log),
        log,
        HeapIngress(JvmHeap.budget, Knob.MAX_REQUEST_BYTES.count(), AdmissionErrorBody),
    )
}

internal fun controlServerFor(
    port: Int,
    heads: Map<String, ManagedHead>,
    config: ConfigService,
    auth: ControlAuth,
    runtime: ControlRuntime = ControlRuntime(),
): ControlServer {
    val signals = signalsFor(heads)
    return ControlServer(
        port,
        heads,
        config,
        auth.guard(),
        auth.log,
        // The health body and the server read one client-version tracker, as ControlPlane wires them.
        health = healthFor(heads, signals = signals, clientVersions = runtime.clientVersions),
        signals = signals,
        runtime = runtime,
    )
}

/** A rig whose /health body is [health], on a port the OS picks. */
internal fun controlServerWith(
    health: ControlHealthReport,
    heads: Map<String, ManagedHead>,
    config: ConfigService,
    auth: ControlAuth,
    runtime: ControlRuntime = ControlRuntime(),
): ControlServer = ControlServer(
    0,
    heads,
    config,
    auth.guard(),
    auth.log,
    health = health,
    signals = signalsFor(heads),
    runtime = runtime,
)
