// NEW: the one place the control-server tests build a ControlServer. Forty-odd rigs construct it, and the
// control server's composition is changing (ControlServer takes its mounts instead of building them), so
// the construction lives here and each rig names only what it exercises. A change to how the server is
// built is a change to this file, not to every rig.
package splice.app.control

import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.util.LogSink

internal fun controlServerFor(
    port: Int,
    heads: Map<String, ManagedHead>,
    config: ConfigService,
    mgmtKey: MgmtKey,
    log: LogSink,
    probes: ControlHealthProbes = ControlHealthProbes(),
    runtime: ControlRuntime = ControlRuntime(),
): ControlServer = ControlServer(port, heads, config, mgmtKey, log, probes = probes, runtime = runtime)
