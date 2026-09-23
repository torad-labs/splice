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
import splice.core.terminal.CYAN
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepthProbe
import splice.core.terminal.DIM
import splice.core.terminal.GREEN
import splice.core.terminal.RESET
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Topology
import splice.core.util.EnvReader

/** [loginHead] is SetupCommand's own sign-in seam, passed through so tests keep constructing one
 *  SetupCommand and the wizard keeps one login path. */
internal class SetupSignIn(
    private val loginHead: HeadSignIn,
    private val palette: CliPalette = CliPalette(ColorDepthProbe(EnvReader(System::getenv)).depth()),
) {

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

    /**
     * The wizard's last screen, and the one moment in splice where a single word gets the whole
     * stage. An operator who has just finished setup wants ONE thing — what to type — and the
     * previous four-row key/value block gave that answer the same weight as the dashboard URL.
     *
     * So: the first launchable command alone, in splice's own tone, surrounded by space. Everything
     * else is a quiet line under it. Heads still waiting on a credential are listed as the command
     * that would finish them, never as a status, because "needs login" is not actionable and
     * `claude-kimi login` is.
     */
    internal fun printNextSteps(topology: Topology) {
        val commands = topology.heads.map { (k, h) -> h.claude.command ?: k }
        val pending = pendingOAuthHeads(topology).map { it.command }.toSet()
        val ready = commands.filterNot { it in pending }
        println()
        println("  " + palette.paint(palette.strong, "Setup complete."))
        println()
        // The hero is a READY head where there is one: sending the operator to a command that will
        // only ask them to sign in is a dead end dressed as a next step.
        val hero = ready.firstOrNull() ?: commands.firstOrNull()
        if (hero != null) {
            println()
            println("      " + palette.strong + palette.signal + hero + palette.off)
            println()
            println()
        }
        val alsoReady = ready.filterNot { it == hero }
        if (alsoReady.isNotEmpty()) {
            println("  " + palette.paint(palette.quiet, "also ready    ") + alsoReady.joinToString("   "))
        }
        for (command in pending) {
            val label = palette.paint(palette.quiet, "needs login   ")
            println("  " + label + palette.paint(palette.signal, "$command login"))
        }
        println()
        // All four affordances the old block named are still named. The redesign moved the launch
        // command to the top and dropped the labels, NOT the dashboard — an earlier cut of this
        // method lost it, and SetupCommandTest caught that rather than the wording change.
        verb("splice status", "see what's running")
        verb("splice doctor", "anything wrong prints its fix")
        verb("splice dashboard", "the panel, in a browser")
    }

    /** One command and what it is for, the command padded so the descriptions form a column. */
    private fun verb(command: String, purpose: String) {
        println("  " + palette.paint(palette.signal, command.padEnd(VERB_W)) + palette.paint(palette.quiet, purpose))
    }
}

/** Width of the command column in the closing block; "splice dashboard" is the longest at 16. */
private const val VERB_W = 18
