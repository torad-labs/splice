// NEW: (split from DoctorCommand.kt, which sits at detekt's 14-function file budget) the doctor
// DAEMON section — is something listening, is it this version, is the running topology still the
// file on disk (JW-04), is the mgmt-key there, and are the state and log dirs actually writable
// (JW-08 names the logs dir, JW-17 proves it rather than printing it), and WHICH state root is
// live (V4-177).
package splice.app.cli.doctor

import splice.core.config.StateDirOrigin
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.daemonclient.DaemonHealth
import java.nio.file.Path

/** The doctor daemon section as a constructed collaborator (Kotlin style law, 2026-08-15: main
 *  sources carry no top-level functions). Holds the write probe it drives and receives the per-head
 *  checks it composes; every member keeps the old function's name so the diff at each call site is a
 *  receiver insertion. */
internal class DoctorDaemonChecks(private val heads: DoctorHeadChecks) {

    private val probeWrite = DoctorProbeWrite()
    private val clientVersion = DoctorClientVersion()
    private val stateLayout = DoctorStateLayout()

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
        val stateInfo = stateLayout.checks(statePaths) + listOf(
            // JW-17: PROVE writability, don't just print the path — an unwritable state root
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
                clientVersion.check(snapshot.health),
                heads.topologyFreshness(snapshot, configPath),
                heads.mgmtKeyCheck(statePaths, snapshot.running),
            ) +
            stateInfo
    }
}

// V4-177's state-layout row lives HERE and not in its own file, deliberately. splice.app.cli sits
// AT the package ratchet's recorded baseline (84 files, ConcentrationLawTest), so an 86th
// Doctor* file in it is a package regression the gate names by number — and the gate's other
// remedy, raising PACKAGE_MAX_FILES, is a dated edit recording that the clump grew. It is still its
// own CLASS rather than a private member, which is the part that mattered: daemonChecks() builds
// StatePaths off the ambient environment, so a row that only appears when NO state variable is set
// is unreachable from a test that drives doctor with a hermetic env. A class takes the StatePaths
// as an argument and every branch becomes a two-line test.
//
// The real clump is the Doctor* cluster itself — 25 of this package's 85 files — and it wants
// splice.app.cli.doctor the way SetupSignIn wanted splice.app.cli.setup in V4-156. That is a
// 25-file move and it is not this row's; it is filed as a followup instead of done here.

/** One reader, so file-private next to its use — the same placement CHECK_TOPOLOGY got. */
private const val CHECK_STATE_LAYOUT = "state layout"

internal class DoctorStateLayout {

    /** V4-177: WHICH state root is live, and whether pre-0.4 history is sitting beside it unread.
     *  The migration is an ADOPTION, not a move, so an operator can otherwise only tell the two
     *  roots apart by running `lsof` on the daemon — and the failure mode of guessing wrong is
     *  reading an empty history and concluding the daemon lost it.
     *
     *  Both arms key off `<root>/state`, never the root directory itself, and that is load-bearing:
     *  the pre-0.4 root is ALSO the wrapped Claude Code config dir of the head keyed `codex`
     *  (LaunchSpecFactory's `~/.claude-$key` collides with it exactly), so it exists on boxes that
     *  never had splice state in it. Only the `state` leaf distinguishes the two, which is why
     *  [StatePaths] resolves it that way rather than probing the root. The root's own spelling stays
     *  in [StatePaths] and nowhere else, this KDoc included — that is the ast-grep wall's subject.
     *
     *  Silent when there is nothing to say — a fresh install on the current layout, or a caller that
     *  pointed the state dir somewhere itself, in which case the old root is not unmigrated, it is
     *  simply not theirs. A row that fires for everyone is a row operators learn to scroll past. */
    internal fun checks(statePaths: StatePaths): List<DoctorCheck> =
        listOfNotNull(probeFault(statePaths), layout(statePaths))

    /** A candidate root that could not be RULED OUT. This is the arm that keeps the whole row
     *  honest: before it existed, a pre-0.4 root the daemon could not READ was indistinguishable
     *  from one that was not there, so the box reported a clean install and said nothing at all —
     *  in exactly the case an operator is about to delete and re-init history that is still on
     *  disk. WARN and not FAIL because the daemon does come up and serve; what it cannot do is
     *  promise this is a fresh box. */
    private fun probeFault(statePaths: StatePaths): DoctorCheck? = statePaths.rootProbeFault?.let { fault ->
        DoctorCheck(
            CHECK_STATE_LAYOUT,
            CheckStatus.WARN,
            "a state root could not be ruled out: $fault — ${statePaths.stateDir} is being used, but " +
                "history under an unreadable root is NOT gone and must not be re-initialised away",
            "make the path above readable (or remove it if it is genuinely not yours), then re-run",
        )
    }

    private fun layout(statePaths: StatePaths): DoctorCheck? = when (statePaths.origin) {
        StateDirOrigin.ADOPTED_LEGACY -> DoctorCheck(
            CHECK_STATE_LAYOUT,
            CheckStatus.INFO,
            "reading the pre-0.4 root ${statePaths.stateDir} in place — it holds this install's " +
                "history, and nothing was copied, moved or deleted to get here",
        )
        StateDirOrigin.DEFAULT -> statePaths.unmigratedLegacyDir?.let { legacy ->
            DoctorCheck(
                CHECK_STATE_LAYOUT,
                CheckStatus.WARN,
                "$legacy still exists and is NOT read — ${statePaths.stateDir} is live, so any " +
                    "usage, perf or compact history under the old root is missing from these numbers",
                "move what you want to keep into ${statePaths.stateDir}, then remove the old state dir",
            )
        }
        StateDirOrigin.OVERRIDE, StateDirOrigin.ENVIRONMENT -> null
    }
}
