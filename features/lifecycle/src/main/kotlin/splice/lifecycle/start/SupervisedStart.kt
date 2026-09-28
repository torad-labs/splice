// NEW: V4-190 (2026-09-21) — the CLI's cold start is UNIT-FIRST, the same law the launch shim
// (app/src/main/dist/bin/splice-launch) carries since V4-189. `splice dashboard` and `splice restart` (and every verb that restarts
// through it: setup, add, the upgrade fallback) reach DaemonLaunch.ensureDaemon, and until this
// file that meant a raw `nohup java … daemon` whenever /health was down — which
// includes every second the supervisor unit spends restarting. Measured: 03:50:42 the unit's daemon
// stopped itself after a stalled turn path, a CLI verb from a Warp shell spawned pid 4138278 in that
// same second, and splice.service then lost the port to it five restarts running.
//
// The route is decided here so DaemonLaunch stays the composer: the unit when it exists on the box
// and no HARNESS SELECTOR points this shell at a daemon of its own; the raw spawn otherwise. The
// selector list is the shim's `unitDefaults()` byte for byte (SupervisedStartTest pins the two).
package splice.lifecycle.start

import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
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

/** The environment selectors that make a shell's daemon its own (a harness, a second install) —
 *  any of them set, even to whitespace, and the supervisor unit could not serve this shell. */
internal val harnessSelectors: List<String> = listOf(
    "SPLICE_CONFIG",
    "XDG_CONFIG_HOME",
    "SPLICE_JAR",
    "SPLICE_SHARE_DIR",
    "SPLICE_STATE_DIR",
    "CLAUDEX_STATE_DIR",
    "SPLICE_CONTROL_PORT",
    "CONTROL_PROXY_PORT",
    "CONTROL_PORT",
)

/** Where a cold start goes. */
internal sealed class ColdStartRoute {
    /** Start [unit] and wait for it; never spawn a daemon beside it. */
    data class Unit(val unit: String) : ColdStartRoute()

    /** Spawn here, for the [reason] named: no unit on the box, or a selector points this shell away. */
    data class Raw(val reason: String) : ColdStartRoute()
}

/** `systemctl --user <args>` as an exit code: 0 is yes. Its output is nobody's business here — a box
 *  with no user manager answers "Failed to connect to bus" on stderr, which the shim silences and the
 *  operator must not see as if it were the cold start's own failure. */
internal fun interface Systemctl {
    operator fun invoke(args: List<String>): Int
}

/** The real one: bounded, output discarded; a missing systemctl or a timeout is a non-zero answer. */
internal class JdkSystemctl(private val timeoutMs: Long = SYSTEMCTL_TIMEOUT_MS) : Systemctl {
    override fun invoke(args: List<String>): Int = Cancellables.runCatchingCancellable {
        val process = ProcessBuilder(listOf("systemctl", "--user") + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            SYSTEMCTL_TIMED_OUT
        }
    }.getOrElse { SYSTEMCTL_ABSENT }
}

/** The supervisor unit a cold start routes to: the operator's SPLICE_SUPERVISOR_UNIT (default
 *  splice.service), read when a route is asked for. A test names its own unit here, so it never loads
 *  this host's config. */
internal fun interface SupervisorUnitName {
    operator fun invoke(): String
}

/** V4-395: the daemon a supervisor unit runs, as far as ownership goes: the HOME it lives under and the
 *  control port it resolves there. */
internal data class UnitDaemon(val home: Path, val controlPort: Int)

/** V4-395: the [UnitDaemon] behind [unit], or null when its environment cannot be read. A test names a
 *  unit's daemon with a lambda; [SystemdUnitDaemon] asks the unit manager. */
internal fun interface UnitDaemonReader {
    operator fun invoke(unit: String): UnitDaemon?
}

/** V4-395: whose the unit is, for one command that wants to act on it. */
internal sealed class UnitOwnership {
    /** The unit's daemon is this shell's home's: the same HOME and the same control port. */
    data object Ours : UnitOwnership()

    /** The unit runs another home's daemon, or cannot be shown to run this one's; [reason] says which. */
    data class Foreign(val reason: String) : UnitOwnership()
}

/** V4-395: the unit manager's environment block, the environment it hands every unit it starts. */
internal object ManagerEnvironment {
    private val escaped = Regex("""\\(.)""")
    private val dollarQuoted = Regex("""\$'(.*)'""", RegexOption.DOT_MATCHES_ALL)

    /** `systemctl --user show-environment`, or null when there is no manager to ask, it answers non-zero,
     *  or it does not answer in time. */
    fun read(timeoutMs: Long = SYSTEMCTL_TIMEOUT_MS): String? {
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

/** V4-395: [UnitDaemonReader] over [ManagerEnvironment]: the daemon a unit starts lives under the manager's
 *  HOME and resolves its control port from that home's splice.toml, so both are read the way the daemon
 *  will read them. The block is the same for every unit, so [invoke] does not ask which one; a unit's own
 *  `Environment=` lines are beyond it. The unit's home is read, never written: an absent splice.toml is
 *  "no TOML layer", where [DaemonSettings.controlPort]'s one-argument form would create it. */
internal class SystemdUnitDaemon(
    private val settings: DaemonSettings,
    private val environment: () -> String? = ManagerEnvironment::read,
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

/** [restarter] runs `systemctl restart`, which blocks until the unit's daemon has drained and
 *  stopped, so it carries a longer deadline than [systemctl]'s millisecond verbs. [unitDaemon] names the
 *  daemon a unit runs so [ownership] can tell whose it is; null skips that check, which is the seam for a
 *  test that does not exercise it, and [DaemonColdStart] always supplies the real reader. The type is
 *  public so [DaemonColdStart]'s one constructor can take it; only this module can build one or call it. */
public class SupervisedStart internal constructor(
    private val systemctl: Systemctl,
    private val envReader: EnvReader,
    private val settings: DaemonSettings,
    private val restarter: Systemctl = systemctl,
    private val unitName: SupervisorUnitName = SupervisorUnitName { settings.supervisorUnit(envReader) },
    private val unitDaemon: UnitDaemonReader? = null,
) {
    internal companion object {
        /** The shipped wiring: the real systemctl, a restarter with the long deadline, and the real
         *  [SystemdUnitDaemon], so no cold start reaches a unit without the ownership check. */
        fun system(env: EnvReader, errors: TerminalOutput): SupervisedStart = SupervisedStart(
            JdkSystemctl(),
            env,
            DaemonSettings(errors),
            restarter = JdkSystemctl(UNIT_RESTART_TIMEOUT_MS),
            unitDaemon = SystemdUnitDaemon(DaemonSettings(errors)),
        )
    }

    /** [unit] is the operator's SPLICE_SUPERVISOR_UNIT (default splice.service), resolved at the
     *  call so a diagnostic that never cold-starts never loads the topology for it. */
    internal fun route(unit: String = unitName()): ColdStartRoute {
        val selector = harnessSelectors.firstOrNull { !envReader(it).isNullOrEmpty() }
        if (selector != null) {
            return ColdStartRoute.Raw("$selector is set, so this shell's daemon is its own, not $unit's")
        }
        // `cat` answers "the unit exists" without starting anything; a missing systemctl is exit 127.
        if (systemctl(listOf("cat", unit)) != 0) {
            return ColdStartRoute.Raw("no $unit on this machine")
        }
        return ColdStartRoute.Unit(unit)
    }

    /** V4-395: whether [unit] runs the daemon this shell is about to act on, on [port]: the same HOME and
     *  the same control port. [route] refuses a shell that names another daemon by an env selector, but a
     *  home that sets none (a second profile under its own HOME) reaches the everyday unit through it, and
     *  a restart then bounces a daemon that is nobody's business here (Marlin's walk, Sep 28, 12:40:45 PM CT).
     *  A unit whose environment cannot be read is not shown to be ours, so it is not treated as ours. */
    internal fun ownership(unit: String, port: Int): UnitOwnership {
        val reader = unitDaemon ?: return UnitOwnership.Ours
        val theirs = reader(unit) ?: return UnitOwnership.Foreign(
            "its environment could not be read, so it cannot be shown to run this home's daemon",
        )
        val ours = UserHome.dir(envReader)
        val differences = listOfNotNull(
            "it runs the daemon of ${theirs.home}, and this shell's home is $ours"
                .takeUnless { sameDirectory(ours, theirs.home) },
            "it serves the control port :${theirs.controlPort}, and this home's is :$port"
                .takeIf { theirs.controlPort != port },
        )
        return if (differences.isEmpty()) UnitOwnership.Ours else UnitOwnership.Foreign(differences.joinToString("; "))
    }

    /** The same directory under a symlinked or unnormalized spelling: two homes named differently are
     *  not two homes. A path that does not resolve is compared as written. */
    private fun sameDirectory(a: Path, b: Path): Boolean = resolved(a) == resolved(b)

    private fun resolved(path: Path): Path =
        Cancellables.runCatchingCancellable { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

    /** `systemctl --user start [unit]`: true when systemd accepted the start job. On a unit waiting
     *  out its restart backoff, systemd takes a manual start as "restart now" (service_start in
     *  systemd 257); on a unit that is still ACTIVE it is a no-op, and the daemon's exit that follows
     *  waits the whole backoff. That is why a restart of the unit's own daemon goes through [restart]
     *  and never stops the daemon and then starts the unit (V4-243). */
    internal fun start(unit: String): Boolean = systemctl(listOf("start", unit)) == 0

    /** V4-243: whether [unit] is active, so the daemon on the port is the one it runs. */
    internal fun active(unit: String): Boolean = systemctl(listOf("is-active", "--quiet", unit)) == 0

    /** V4-243: `systemctl --user restart [unit]`. systemd stops the daemon with SIGTERM, which drains
     *  its in-flight turns through the same daemon.stop() the shutdown route runs, then starts it at
     *  once; a manual restart also resets the unit's restart counter, so no backoff is waited out.
     *  Returns when the restart job is done, which is why it runs on [restarter]. */
    internal fun restart(unit: String): Boolean = restarter(listOf("restart", unit)) == 0
}

// why: `systemctl --user cat|start` answer in milliseconds; a manager that hangs longer than this is
// not one to wait on, and the raw route is the answer a box without a manager already gets.
private const val SYSTEMCTL_TIMEOUT_MS = 15_000L

// why: `systemctl --user restart` returns only once the daemon has drained and stopped (its own halt
// floor is 57 s) and the new one has started; 120 s sits above systemd's default 90 s stop timeout, so
// a stop that overruns is systemd's to end, not this deadline's.
internal const val UNIT_RESTART_TIMEOUT_MS = 120_000L

// why: the shell's own code for a command that hit its deadline, so a log reader sees a familiar number.
private const val SYSTEMCTL_TIMED_OUT = 124

// why: the shell's own code for a command that is not there, so a log reader sees a familiar number.
private const val SYSTEMCTL_ABSENT = 127
