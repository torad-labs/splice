// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// DAEMON section — is something listening, is it this version, is the running topology still the
// file on disk (JW-04), is the mgmt-key there, and are the state and log dirs actually writable
// (JW-08 names the logs dir, JW-17 proves it rather than printing it).
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.GATEWAY_VERSION
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Files
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
        val daemon = when (val running = snapshot.healthVersion) {
            null -> DoctorCheck(CHECK_DAEMON, CheckStatus.INFO, "stopped (starts on first launch)")
            GATEWAY_VERSION ->
                DoctorCheck(CHECK_DAEMON, CheckStatus.OK, "running $GATEWAY_VERSION on :${snapshot.port}")
            else -> DoctorCheck(
                CHECK_DAEMON,
                CheckStatus.WARN,
                "running $running but this CLI is $GATEWAY_VERSION",
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
            listOfNotNull(topologyFreshness(snapshot, configPath), mgmtKeyCheck(statePaths, snapshot.running)) +
            stateInfo
    }

    /** JW-04: is the file on disk still the one the daemon booted from? Compared digest-to-digest
     *  (the doctor hashes the local file; the daemon published what it parsed), with the daemon's
     *  own topologyStale recompute as the belt. Fail-open: no health, no published digest, or an
     *  unreadable local file all mean no row — never a fabricated verdict. */
    private fun topologyFreshness(snapshot: DaemonSnapshot, configPath: Path?): DoctorCheck? {
        val h = snapshot.health
        val booted = h?.topologyDigest?.takeIf { it.isNotEmpty() }
        val local = booted?.let { configPath?.let(TopologyLoader::currentDigest) } ?: return null
        return if (local == booted && h?.topologyStale != true) {
            DoctorCheck("topology", CheckStatus.OK, "running config matches the file on disk")
        } else {
            DoctorCheck(
                "topology",
                CheckStatus.WARN,
                "splice.toml changed since the daemon booted — the running topology is stale",
                "splice restart",
            )
        }
    }

    // LOST-COVERAGE fix: a missing mgmt-key file 401s every bearer endpoint. Present → OK; absent while
    // the daemon RUNS is a hard FAIL (the daemon holds its boot-minted key in memory and re-reads no
    // file, and `splice restart` can't authenticate the shutdown without the file — so the honest fix
    // is a manual kill, after which the next launch re-mints via MgmtKey.ensure()); absent while stopped
    // is benign (minted on first launch).
    private fun mgmtKeyCheck(statePaths: StatePaths, daemonRunning: Boolean): DoctorCheck {
        val keyFile = statePaths.mgmtKeyFile
        val present = Cancellables
            .runCatchingCancellable { Files.readString(keyFile).trim().isNotEmpty() }
            .getOrDefault(false)
        return when {
            present -> DoctorCheck("mgmt-key", CheckStatus.OK, keyFile.toString())
            daemonRunning -> DoctorCheck(
                "mgmt-key",
                CheckStatus.FAIL,
                "missing at $keyFile — admin endpoints will 401",
                "terminate the daemon process manually; the next launch re-mints the key",
            )
            else -> DoctorCheck("mgmt-key", CheckStatus.INFO, "minted on first launch")
        }
    }
}
