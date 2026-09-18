// NEW: V4-156 — `splice setup`'s post-install tail: which heads still need an OAuth sign-in, the
// per-head sign-in prompts, and the closing next-steps block. Moved verbatim out of
// SetupCommand.kt, which reached concentration band HIGH.
//
// DECOMPOSED FOR THE RATIO, NOT FOR A DEFECT: SetupCommand.kt did not change at all in the range
// measured (own 0 percent, dC +0.0, --since 608f63b9); it crossed the line because its neighbours
// correctly got smaller. Nothing here was wrong where it was. The section moved rather than another
// because it is the one part of the wizard that runs AFTER the install and reads only the topology.
//
// IN A SUBPACKAGE, not beside SetupCommand: splice.app.cli is the repo's most crowded package and the
// concentration ratchet gates its file count, so a decomposition that dropped two more files into it
// would have moved the crowding rather than reduced it. splice.app.cli.prompt was already here, so
// the shape is the tree's own.
package splice.app.cli.setup

import splice.app.cli.AdminSupport
import splice.app.cli.BOLD
import splice.app.cli.CYAN
import splice.app.cli.DIM
import splice.app.cli.GREEN
import splice.app.cli.HeadSignIn
import splice.app.cli.PendingOAuthHead
import splice.app.cli.RESET
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Topology

/** [loginHead] is SetupCommand's own sign-in seam, passed through so tests keep constructing one
 *  SetupCommand and the wizard keeps one login path. */
internal class SetupSignIn(private val loginHead: HeadSignIn) {

    private fun pendingOAuthHeads(topology: Topology): List<PendingOAuthHead> =
        topology.heads.entries.mapNotNull { (key, head) ->
            val provider = topology.providers[head.provider] ?: return@mapNotNull null
            if (AuthKindRegistry.isOAuth(provider.auth.kind) &&
                !authPresent(provider.auth.file, provider.auth.kind)
            ) {
                PendingOAuthHead(key, head.claude.command ?: key)
            } else {
                null
            }
        }

    internal suspend fun signInPendingHeads(topology: Topology): Boolean {
        val pending = pendingOAuthHeads(topology)
        if (pending.isEmpty()) {
            println("$GREEN✓$RESET wrapper installed. Set OPENROUTER_API_KEY before launching.")
            return true
        }
        println(
            "$DIM  Subscription heads reuse each vendor CLI's public OAuth client identity, signed in " +
                "separately for splice (its own credential file, any account) — " +
                "unofficial; use at your own risk.$RESET",
        )
        var ok = true
        for ((key, command) in pending) {
            if (AdminSupport.confirm("Sign in to $CYAN$command$RESET now?", default = true)) {
                if (!loginHead(key)) ok = false
            } else {
                println("  ${DIM}skipped — sign in later with: $command login$RESET")
            }
        }
        return ok
    }

    private fun authPresent(file: String?, kind: String): Boolean {
        val path = file ?: AuthKindRegistry.defaultAuthFileFor(kind) ?: return false
        return AdminSupport.authPresent(path)
    }

    internal fun printNextSteps(topology: Topology) {
        println()
        println("${BOLD}You're set.$RESET")
        val commands = topology.heads.map { (k, h) -> h.claude.command ?: k }
        println("  Launch      ${commands.joinToString("$DIM · $RESET") { "$CYAN$it$RESET" }}")
        println("  Dashboard   ${CYAN}splice dashboard$RESET")
        println("  Status      ${CYAN}splice status$RESET")
        println("  Checkup     ${CYAN}splice doctor$RESET $DIM— anything wrong prints its fix$RESET")
    }
}
