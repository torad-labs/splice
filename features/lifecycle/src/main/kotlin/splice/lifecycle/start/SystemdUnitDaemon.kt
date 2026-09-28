// NEW: V4-395 — the production reader of a supervisor unit's daemon: the systemd user manager's environment
// block, which is the environment it hands every unit it starts. Split from SupervisedStart so the route stays
// the route and the ownership reading is one file with one reason to change.
package splice.lifecycle.start

import splice.core.config.UserHome
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.daemonclient.DaemonSettings
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The unit manager's environment block as text, or null when it cannot be had. */
internal fun interface ManagerEnvironmentBlock {
    operator fun invoke(): String?
}

/** The real one: `systemctl --user show-environment`, bounded; null when there is no manager to ask, it
 *  answers non-zero, or it does not answer in time. */
internal class SystemctlEnvironmentBlock(private val timeoutMs: Long = SYSTEMCTL_TIMEOUT_MS) :
    ManagerEnvironmentBlock {
    override fun invoke(): String? {
        val process = Cancellables.runCatchingCancellable {
            ProcessBuilder(listOf("systemctl", "--user", "show-environment"))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse { return null }
        return try {
            val bytes = CompletableFuture.supplyAsync { process.inputStream.readAllBytes() }
                .get(timeoutMs, TimeUnit.MILLISECONDS)
            val answered = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) && process.exitValue() == 0
            bytes.toString(Charsets.UTF_8).takeIf { answered }
        } catch (_: TimeoutException) {
            null
        } finally {
            process.destroyForcibly()
        }
    }
}

/** The unit manager's environment block, parsed. */
internal object ManagerEnvironment {
    private val escaped = Regex("""\\(.)""")
    private val dollarQuoted = Regex("""\$'(.*)'""", RegexOption.DOT_MATCHES_ALL)

    /** The block's `NAME=value` lines. systemd writes a value holding whitespace or shell metacharacters
     *  as `NAME=$'value'` with backslash escapes; every other value is written bare. A line with no name
     *  or no equals sign is not a variable. */
    fun parse(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val at = line.indexOf('=')
            if (at > 0) line.substring(0, at) to unquote(line.substring(at + 1)) else null
        }
        .toMap()

    private fun unquote(value: String): String =
        dollarQuoted.matchEntire(value)?.let { quoted ->
            escaped.replace(quoted.groupValues[1]) { match ->
                when (val char = match.groupValues[1]) {
                    "n" -> "\n"
                    "t" -> "\t"
                    else -> char
                }
            }
        } ?: value
}

/** [UnitDaemonReader] over [ManagerEnvironment]: the daemon a unit starts lives under the manager's HOME and
 *  resolves its control port from that home's splice.toml, so both are read the way the daemon will read
 *  them. The block is the same for every unit, so [invoke] does not ask which one; a unit's own
 *  `Environment=` lines are beyond it. The unit's home is read, never written: an absent splice.toml is "no
 *  TOML layer", where [DaemonSettings.controlPort]'s one-argument form would create it. */
internal class SystemdUnitDaemon(
    private val settings: DaemonSettings,
    private val environment: ManagerEnvironmentBlock = SystemctlEnvironmentBlock(),
) : UnitDaemonReader {
    override fun invoke(unit: String): UnitDaemon? {
        val block = environment() ?: return null
        return daemonUnder(ManagerEnvironment.parse(block))
    }

    private fun daemonUnder(manager: Map<String, String>): UnitDaemon? {
        val env = EnvReader { manager[it] }
        // No HOME in the block would let UserHome fall back to THIS JVM's, and the answer would be ours.
        val home = UserHome.environmentHome(env) ?: return null
        val topology = topologyAt(TopologyLoader.configPath(env)).getOrElse { return null }
        return UnitDaemon(Path.of(home), settings.controlPort(topology, env))
    }

    /** The topology at [config]: success(null) when the file is absent, a failure when it is unreadable. */
    private fun topologyAt(config: Path): Result<Topology?> =
        if (Files.isRegularFile(config)) {
            Cancellables.runCatchingCancellable { TopologyLoader.parse(Files.readString(config)) }
        } else {
            Result.success(null)
        }
}
