// NEW: the control plane's per-head readings and /health body, moved out of ControlPlane.kt by their own seam, behavior
// unchanged (concentration, Oct 13, splice-lead). The heads route and /health share the readings; /health adds the
// head set's verdict, the booted config's identity and whether the MCP hub runs.
package splice.app

import splice.app.control.FailedHeads
import splice.app.control.ManagedHead
import splice.app.control.RuntimeNotAnswering
import splice.app.control.RuntimeUnread
import splice.app.control.TopologyDigest
import splice.app.control.TurnPathStalled
import splice.app.control.api.ControlHealthReport
import splice.app.control.api.HeadReadiness
import splice.app.control.api.HeadSignals
import splice.app.control.api.ServingProcess
import splice.app.control.api.TypedCommand
import splice.app.daemon.BootedTopology
import splice.app.daemon.Generations
import splice.app.daemon.TopologyStaleness
import splice.app.head.HeadProbeReadings
import splice.client.wrap.WrappedHead
import splice.configuration.topology.TopologyStale
import splice.core.version.ClientVersionTracker
import splice.launch.wrap.WrappedClaudeLaunch
import splice.sessions.registry.SessionSource
import java.nio.file.Path

internal class ControlHealth(
    /** JW-04 + V4-127: the booted config's identity and what it declared. */
    private val topology: BootedTopology,
    private val clientVersions: ClientVersionTracker,
    /** The MCP wiring, whose hub verdict /health reports once the host is built. */
    private val mcp: ControlMcpWiring,
) {
    /** The per-head readings the heads route and /health share, read from the daemon's probes per request. */
    /** [home] is where the wrap of plain `claude` is kept: the name a person types for the Claude head is read
     *  from it per request, the same file a launch reads, so a wrap or an unwrap shows at the next reply. */
    fun signals(
        heads: Map<String, ManagedHead>,
        sessions: SessionSource,
        probes: HeadProbeReadings,
        home: Path,
    ): HeadSignals {
        val claude = WrappedClaudeLaunch(WrappedHead(home))
        return HeadSignals(
            heads,
            RuntimeNotAnswering { probes.runtimeNotAnswering() },
            sessions,
            RuntimeUnread { probes.runtimeUnread() },
            TypedCommand(claude::typed),
        )
    }

    /** The /health body: the head set's verdict, the per-head readings, and the booted config's identity. */
    fun report(
        heads: Map<String, ManagedHead>,
        failedHeads: FailedHeads,
        headCount: Int,
        probes: HeadProbeReadings,
        signals: HeadSignals,
    ): ControlHealthReport {
        // V4-162: the version the daemon RUNS, which a window-only edit moves (TopologyWindows). A
        // control plane built without one publishes the booted digest and compares bytes, as before.
        val running = topology.running
        return ControlHealthReport(
            readiness = HeadReadiness(heads, failedHeads, headCount, TurnPathStalled { probes.stalledKeys() }),
            signals = signals,
            topologyDigest = TopologyDigest { running?.digest() ?: topology.digest },
            topologyStale = running?.let { TopologyStale(it::stale) }
                ?: TopologyStaleness.probe(topology.path, topology.digest),
            clientVersions = clientVersions,
            mcpHub = mcp.hub,
            process = ServingProcess(ProcessHandle.current().pid(), Generations().launcher()),
        )
    }
}
