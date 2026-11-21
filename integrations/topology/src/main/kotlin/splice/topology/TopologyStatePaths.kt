// NEW: V4-109 — the ONE mapping from splice.toml's [daemon].state_dir to the state paths, shared by
// the daemon (DaemonProcess) and every CLI reader of what the daemon writes there (the mgmt key, the
// state config layer, daemon.log, traces, perf). Each used to build StatePaths from the environment
// alone, so a declared state_dir moved the daemon and left `splice restart` reading a key from a
// directory the daemon had never written.
package splice.topology

import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.util.EnvReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

public class TopologyStatePaths(
    private val envReader: EnvReader = EnvReader(System::getenv),
    private val homeDir: Path = UserHome.dir(envReader),
) {
    /** The daemon's resolution for [topology]: a usable `[daemon].state_dir` wins over everything (it
     *  is StatePaths' baseOverride, above the environment), otherwise the environment and the default
     *  layout decide. An unusable value keeps the default rather than failing: the key was inert
     *  before V4-109, so a value operators were free to write must not become a failure now that it
     *  means something (NEVER-BELOW-STATUS-QUO); doctor's state_dir row names the dropped value.
     *  Blank is absent for the same reason. A leading `~/`, or a bare `~`, is this home, as `config_dir` and the
     *  compaction files read it (Oct 13): Paths.get took it as written, a folder named "~" under the
     *  daemon's working directory. */
    public fun of(topology: Topology?): StatePaths {
        val declared = topology?.daemon?.stateDir?.takeIf { it.isNotBlank() } ?: return fromEnvironment()
        // The parameter is `_` because the exception is DELIBERATELY unused: an unparseable path is
        // the documented fallback above, not a swallowed failure.
        val path = try {
            when {
                declared == "~" -> homeDir
                declared.startsWith("~/") -> homeDir.resolve(declared.removePrefix("~/"))
                else -> Paths.get(declared)
            }
        } catch (_: InvalidPathException) {
            return fromEnvironment()
        }
        return StatePaths(baseOverride = path)
    }

    /** [of] the splice.toml on disk, read WITHOUT materializing a starter: a process asking where the
     *  daemon keeps its state must not write config as a side effect. An absent, unreadable or
     *  unparseable file declares no override, and that is the daemon's answer too: it cannot boot
     *  on a file it cannot parse, and on an absent one it writes the starter, which declares none. */
    public fun current(): StatePaths = of(onDisk())

    /** The effective control port for [topology] (null: no file, or one that could not be read), by the daemon's exact
     *  TOML < state < env precedence: the CLI's DaemonSettings asks it, and so does `splice sessions`, which names this
     *  machine's door on that port (LaunchDoors) without reaching the daemon. No topology still resolves through the
     *  layered config: the old null-branch returned the hardcoded default, silently IGNORING the state config.json and
     *  SPLICE_CONTROL_PORT layers, which broke hermetic test rigs and diverged from the launch shim's own resolution
     *  (JW-05 discovery, 2026-08-07). */
    public fun controlPort(topology: Topology?): Int = ConfigService(
        // V4-109: the state config layer lives in the daemon's RESOLVED state dir.
        of(topology),
        headOverrides = topology?.let { TopologyKnobLayer(it).configOverrides() } ?: emptyMap(),
        envReader = envReader,
    ).getConfig().controlPort

    private fun onDisk(): Topology? {
        val path = TopologyLoader.configPath(envReader)
        return try {
            TopologyLoader.parse(Files.readString(path))
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun fromEnvironment(): StatePaths = StatePaths(envReader = envReader, homeDir = homeDir)
}
