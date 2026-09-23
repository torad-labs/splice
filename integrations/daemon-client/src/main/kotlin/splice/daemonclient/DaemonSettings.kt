// NEW: what a CLI process resolves about the local daemon before it can ask it anything — its
// control port and the systemd unit that supervises it — through the daemon's exact TOML < state <
// env precedence. Moved from AdminSupport (LAYOUT-01) so a verb outside app resolves the same port
// the daemon binds.
package splice.daemonclient

import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.topology.TopologyLoader

/** [errors] receives the one diagnostic a resolution can raise: an unreadable splice.toml. */
public class DaemonSettings(private val errors: TerminalOutput) {

    /** The effective control port using the daemon's exact TOML < state < env precedence. */
    public fun controlPort(envReader: EnvReader): Int {
        val configPath = TopologyLoader.configPath(envReader)
        // DR-41b, same F3 lesson RestartCommand already carries: a corrupt TOML silently
        // degrading to default ports makes a RUNNING daemon look stopped. Say it (on [errors], stderr
        // in every CLI — stdout belongs to the verb's own output).
        val topology = Cancellables.runCatchingCancellable {
            TopologyLoader.loadOrMaterialize(configPath)
        }.onFailure {
            errors.line(
                "splice: could not read $configPath (${SafeFailureText.render(it)}) — " +
                    "using default ports; a running daemon may appear stopped",
            )
        }.getOrNull()
        return controlPort(topology, envReader)
    }

    /** V4-176: the systemd user unit that supervises this install, by name, through the same
     *  TOML < state < env precedence as [controlPort]. splice does not own the unit; it reads the
     *  name so a box whose packager called it something else is not permanently "unsupervised".
     *  A blank value falls back to the knob's declared default rather than asking systemctl about
     *  an empty unit name. */
    public fun supervisorUnit(envReader: EnvReader): String {
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-20 (V4-176): an unreadable TOML means "no TOML layer" here, which is the answer the state and env layers below are then decided by; [controlPort] above already PRINTS the diagnostic for the very same file on the very same upgrade path, and a second copy would report one corrupt config twice.
        val topology = Cancellables.runCatchingCancellable {
            TopologyLoader.loadOrMaterialize(TopologyLoader.configPath(envReader))
        }.getOrNull()
        val unit = ConfigService(
            StatePaths(envReader = envReader),
            headOverrides = topology?.let { TopologyKnobLayer(it).configOverrides() } ?: emptyMap(),
            envReader = envReader,
        ).getConfig().supervisorUnit
        return unit.ifBlank { Knob.SUPERVISOR_UNIT.default as String }
    }

    /** Same, from an already-loaded (or absent) topology — doctor uses this so a diagnostic
     *  never MATERIALIZES the starter config as a side effect. [envReader] threads through the
     *  whole port resolution (StatePaths + ConfigService env layer) so a hermetic caller never
     *  reads the real process environment or state dir. */
    public fun controlPort(topology: Topology?, envReader: EnvReader): Int =
        ConfigService(
            StatePaths(envReader = envReader),
            // No topology (fresh machine / broken TOML) still resolves through the layered config:
            // the old null-branch returned the hardcoded default, silently IGNORING the state
            // config.json and SPLICE_CONTROL_PORT layers — which both broke hermetic test rigs
            // (an ambient real daemon answered instead) and diverged from the launch shim's own
            // resolution (JW-05 discovery, 2026-08-07).
            headOverrides = topology?.let { TopologyKnobLayer(it).configOverrides() } ?: emptyMap(),
            envReader = envReader,
        ).getConfig().controlPort
}
