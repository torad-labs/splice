// NEW: V4-177 — the doctor row that says WHICH state root is live. Its own file, and its own
// collaborator, for the same reason DoctorProbeWrite and DoctorClientVersion are: DoctorDaemonChecks
// builds StatePaths off the ambient environment, so a row that only appears when NO state variable
// is set cannot be reached from a test that drives doctor with a hermetic env. Handed a StatePaths,
// this answers in one call and every branch is a two-line test.
package splice.app.cli

import splice.core.config.StateDirOrigin
import splice.core.config.StatePaths

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
    internal fun check(statePaths: StatePaths): DoctorCheck? = when (statePaths.origin) {
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
