// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// DAEMON section — is something listening, is it this version, is the running topology still the
// file on disk (JW-04), is the mgmt-key there, and are the state and log dirs actually writable
// (JW-08 names the logs dir, JW-17 proves it rather than printing it).
package splice.app.cli

import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.EnvReader
import java.nio.file.Path

/** The doctor daemon section as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). Holds the write probe it drives and receives the per-head
 *  checks it composes; every member keeps the old function's name so the diff at each call site is a
 *  receiver insertion. */
internal class DoctorDaemonChecks(private val heads: DoctorHeadChecks) {

    private val probeWrite = DoctorProbeWrite()

    internal fun daemonChecks(
        snapshot: DaemonSnapshot,
        envReader: EnvReader,
        topology: Topology?,
        configPath: Path? = null,
    ): List<DoctorCheck> {
        val statePaths = StatePaths(envReader = envReader)
        val expected = DaemonHealth().cliVersion()
        val daemon = when (val running = snapshot.healthVersion) {
            null -> DoctorCheck(CHECK_DAEMON, CheckStatus.INFO, "stopped (starts on first launch)")
            expected ->
                DoctorCheck(CHECK_DAEMON, CheckStatus.OK, "running $expected on :${snapshot.port}")
            else -> DoctorCheck(
                CHECK_DAEMON,
                CheckStatus.WARN,
                "running $running but this CLI is $expected",
                FIX_RESTART,
            )
        }
        // daemon.lock is a flock advisory gate whose FILE persists after the daemon exits, so its mere
        // presence proves nothing about liveness (DaemonLock.kt) — report the path only, never a
        // fabricated staleness WARN. The state dir path is the same kind of orientation detail.
        val stateInfo = listOf(
            // JW-17: PROVE writability, don't just print the path — an unwritable ~/.claude-codex
            // degrades daemon.log, config persistence, and usage/perf/compact appends all silently.
            probeWrite.writableProbe("state dir", statePaths.stateDir),
            // JW-08: daemon.log lives in the SIBLING logs dir, not state/ — printing only the state
            // dir sent operators to a directory that does not contain the logs. Name the real path
            // and the verb that reaches it (works with the daemon stopped).
            probeWrite.writableProbe(
                "logs dir",
                statePaths.logsDir,
                "${statePaths.logsDir.resolve("daemon.log")}  (splice logs)",
            ),
            DoctorCheck("daemon.lock", CheckStatus.INFO, statePaths.daemonLockFile.toString()),
        )
        return listOf(daemon) + heads.headChecks(snapshot, topology) +
            listOfNotNull(
                heads.topologyFreshness(snapshot, configPath),
                heads.mgmtKeyCheck(statePaths, snapshot.running),
            ) +
            stateInfo
    }
}
