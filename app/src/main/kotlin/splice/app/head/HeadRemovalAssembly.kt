// NEW: Oct 13, 2026 — split from HeadRemoval.kt along its seam: what a removal is built from is the control server's
// parts, and only this file names them, so the removal itself imports none of splice.app.control.
package splice.app.head

import splice.app.control.ConsolePorts
import splice.app.control.ControlRuntime
import splice.app.control.ManagedHead
import splice.core.config.ConfigService
import splice.core.topology.TopologyWriterSource
import splice.core.util.LogSink

/** Assembles the removal from what the control server already holds, so the server names none of its parts. */
internal class HeadRemovalAssembly(
    private val config: ConfigService,
    private val ports: ConsolePorts,
    private val runtime: ControlRuntime,
    private val heads: Map<String, ManagedHead>,
    private val log: LogSink,
) {
    fun build(): HeadRemoval = HeadRemoval(
        TopologyWriterSource { ports.topology },
        WrapperBinSource { ports.wrapperBin },
        config.statePaths,
        HeadRemovalFacts(config.statePaths, heads, RegisteredSessions { runtime.sessions }),
        log,
    )
}
