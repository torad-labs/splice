// NEW: (JW-02, split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the
// per-head verdict inside the daemon section: the heads/readyHeads/failedHeads counters and the
// per-head TCP probe. headSummary lives HERE, with headChecks, its only caller — deliberately not
// in DoctorRuntime.kt, which already reads over the 1.8 concentration gate and would take the mass
// into an existing offender.
package splice.app.cli

import splice.app.TopologyLoader
import splice.core.config.StatePaths
import splice.core.topology.Topology
import java.nio.file.Files
import java.nio.file.Path

/** The doctor per-head checks as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). Receives the ONE [DoctorRuntime] the run holds, so the
 *  turn-path verdict here and the runtime section's own rows read the same instrument. Every member
 *  keeps the old function's name so the diff at each call site is a receiver insertion. */
internal class DoctorHeadChecks(private val doctorRuntime: DoctorRuntime) {

    /** JW-02: the degraded-boot rows doctor was structurally blind to. /health has carried
     *  heads/readyHeads/failedHeads since the shim's converge-wait; a green "daemon running" over
     *  dead heads plus "Everything checks out." was the exact lie this command exists to prevent.
     *  Per-head TCP probes distinguish a bound-but-unassembled head from an unbound one; probed only
     *  while the daemon runs (a stopped daemon's closed ports are expected, not findings). */
    internal fun headChecks(snapshot: DaemonSnapshot, topology: Topology?): List<DoctorCheck> {
        val h = snapshot.health ?: return emptyList()
        val perHead = topology?.heads.orEmpty().map { (key, cfg) ->
            val listening = AdminSupport.controlPortBound(cfg.port)
            DoctorCheck(
                "head $key",
                if (listening) CheckStatus.INFO else CheckStatus.WARN,
                ":${cfg.port} ${if (listening) "listening" else "not listening"}",
            )
        }
        return listOfNotNull(doctorRuntime.turnPathCheck(h), headSummary(h)) + perHead
    }

    /** The heads/readyHeads/failedHeads verdict; null on a foreign/ancient listener without the
     *  counters (a real daemon always sends all three) — nothing honest to report then. */
    private fun headSummary(h: HealthView): DoctorCheck? {
        val heads = h.heads
        val ready = h.readyHeads
        val failed = h.failedHeads
        val countersPresent = listOf(heads, ready, failed).none { it == null }
        if (!countersPresent) return null
        checkNotNull(heads)
        checkNotNull(ready)
        checkNotNull(failed)
        return when {
            failed > 0 -> DoctorCheck(
                "heads",
                CheckStatus.FAIL,
                "$failed of $heads head(s) FAILED to start",
                "splice restart (then: splice logs --head <key> --tail 50 to see why)",
            )
            ready + failed < heads ->
                DoctorCheck("heads", CheckStatus.WARN, "still converging: $ready ready + $failed failed of $heads")
            else -> DoctorCheck("heads", CheckStatus.OK, "$ready of $heads head(s) ready")
        }
    }

    /** JW-04: is the file on disk still the one the daemon booted from? Compared digest-to-digest
     *  (the doctor hashes the local file; the daemon published what it parsed), with the daemon's
     *  own topologyStale recompute as the belt. Fail-open: no health, no published digest, or an
     *  unreadable local file all mean no row — never a fabricated verdict. */
    internal fun topologyFreshness(snapshot: DaemonSnapshot, configPath: Path?): DoctorCheck? {
        val h = snapshot.health
        val booted = h?.topologyDigest?.takeIf { it.isNotEmpty() }
        val local = booted?.let { configPath?.let(TopologyLoader::currentDigest) } ?: return null
        return if (local == booted && h.topologyStale != true) {
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
    internal fun mgmtKeyCheck(statePaths: StatePaths, daemonRunning: Boolean): DoctorCheck {
        val keyFile = statePaths.mgmtKeyFile
        val present = runCatching { Files.readString(keyFile).trim().isNotEmpty() }.getOrDefault(false)
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
