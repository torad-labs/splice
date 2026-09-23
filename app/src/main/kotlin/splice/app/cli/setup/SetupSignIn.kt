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
import splice.app.cli.auth.CliSignIn
import splice.core.terminal.CliPalette
import splice.core.terminal.ColorDepthProbe
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.terminal.ConsoleConfirm

/** [loginHead] is SetupCommand's own sign-in seam, passed through so tests keep constructing one
 *  SetupCommand and the wizard keeps one login path. [env] is SetupCommand's threaded environment
 *  (the V4-177 rule beside that constructor): it decides both which heads hold a credential and
 *  how much colour the terminal gets, so neither answer can come from a different process env. */
internal class SetupSignIn(
    private val loginHead: HeadSignIn,
    private val env: EnvReader,
    private val palette: CliPalette = CliPalette(ColorDepthProbe(env).depth()),
) {

    private val signIn = CliSignIn()
    private val confirm = ConsoleConfirm()

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
            // Confirmation only. This line used to add "Set OPENROUTER_API_KEY before launching."
            // unconditionally — on machines where the key was set, and on topologies with no
            // openrouter head at all. The close below names every head still missing a credential
            // and the command that fixes it, so an instruction here could only repeat or contradict it.
            println(palette.paint(palette.live, "\u2713") + " wrapper installed.")
            return true
        }
        println(
            palette.paint(
                palette.quiet,
                "  Subscription heads reuse each vendor CLI's public OAuth client identity, signed in " +
                    "separately for splice (its own credential file, any account) — " +
                    "unofficial; use at your own risk.",
            ),
        )
        var ok = true
        for ((key, command) in pending) {
            if (confirm("Sign in to ${palette.paint(palette.signal, command)} now?", default = true)) {
                if (!loginHead(key)) ok = false
            } else {
                println("  " + palette.paint(palette.quiet, "skipped — sign in later with: $command login"))
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
     * So: the next thing to type, alone, in splice's own tone, surrounded by space. That is the
     * first head that can launch, or — when none can yet — the login that makes one launchable.
     * Everything else is a quiet line under it. A head still missing a credential is listed as the
     * command that finishes it, never as a status, because "needs a key" is not actionable and
     * `claude-or login` is.
     *
     * The block does NOT announce completion. SetupCommand ends on the frame's outro, and the
     * first cut of this method opened with its own "Setup complete." — so the screen said done
     * twice, the way the old "You're set." heading had against "Toolkit ready!".
     */
    internal fun printNextSteps(topology: Topology) {
        // READINESS IS THE PREDICATE `splice status` USES. The first cut asked only whether an
        // OAuth head had its token file, so an api-key head with no key counted as ready — and on a
        // topology that listed it first, the wizard's largest word was a command that fails on
        // launch. A head whose provider is missing is skipped, exactly as status skips it.
        val heads = topology.heads.entries.mapNotNull { (key, head) ->
            val provider = topology.providers[head.provider] ?: return@mapNotNull null
            (head.claude.command ?: key) to credentialed(key, provider)
        }
        val ready = heads.filter { it.second }.map { it.first }
        val pending = heads.filterNot { it.second }.map { "${it.first} login" }
        val hero = ready.firstOrNull() ?: pending.firstOrNull()
        println()
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
        for (login in pending.filterNot { it == hero }) {
            println("  " + palette.paint(palette.quiet, "needs login   ") + palette.paint(palette.signal, login))
        }
        println()
        // All four affordances the old block named are still named. The redesign moved the launch
        // command to the top and dropped the labels, NOT the dashboard — an earlier cut of this
        // method lost it, and SetupCommandTest caught that rather than the wording change.
        verb("splice status", "see what's running")
        verb("splice doctor", "anything wrong prints its fix")
        verb("splice dashboard", "the panel, in a browser")
        // Room before the frame's outro, which renders at column 0 and would otherwise read as one
        // more row of this list.
        println()
    }

    /** A head that can launch as-is: the client's own login, or a credential splice can find. */
    private fun credentialed(key: String, provider: ProviderConfig): Boolean =
        AuthKindRegistry.from(provider.auth.kind) == AuthKind.Client ||
            signIn.credentialConfigured(key, provider, env)

    /** One command and what it is for, the command padded so the descriptions form a column. */
    private fun verb(command: String, purpose: String) {
        println("  " + palette.paint(palette.signal, command.padEnd(VERB_W)) + palette.paint(palette.quiet, purpose))
    }
}

/** Width of the command column in the closing block; "splice dashboard" is the longest at 16. */
private const val VERB_W = 18
