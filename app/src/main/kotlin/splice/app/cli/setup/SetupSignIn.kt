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
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.lifecycle.prices.PRICE_REFRESH_HOST
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
        // No head waits for a sign-in: nothing to say. Until Nov 11, 2026 this printed "wrapper installed." under the
        // spinner's own "Installed wrappers". The close below names every head still missing a credential and the
        // command that fixes it.
        if (pending.isEmpty()) return true
        println(
            palette.paint(
                palette.quiet,
                "  Each subscription head signs in separately for splice (its own credential file, any account).",
            ),
        )
        var ok = true
        for ((key, command) in pending) {
            if (confirm("Sign in to ${palette.paint(palette.signal, command)} now?", default = true)) {
                if (!loginHead(key)) ok = false
            } else {
                println("  " + palette.paint(palette.quiet, "skipped; sign in later with: splice login $key"))
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
     * So: the next thing to type, alone, in splice's own tone, surrounded by space. That is [launch], the command that
     * starts Claude Code on splice's door (`claude-splice` when setup linked it), with or without a plan: the door
     * works with nothing signed in. Custom head commands are deprecated, so no plan's own command is the hero any more;
     * each plan whose credential is in place is named by what to type in `claude` for its models (`/model <selector>`).
     * A plan still missing a credential is listed as the command that finishes it, never as a status, because "needs a
     * key" is not actionable and `splice login openrouter` is.
     *
     * The block does NOT announce completion. SetupCommand ends on the frame's outro, and the
     * first cut of this method opened with its own "Setup complete." — so the screen said done
     * twice, the way the old "You're set." heading had against "Toolkit ready!".
     */
    internal fun printNextSteps(topology: Topology, launch: String = LAUNCH_CLAUDE) {
        // READINESS IS THE PREDICATE `splice status` USES. The first cut asked only whether an
        // OAuth head had its token file, so an api-key head with no key counted as ready — and on a
        // topology that listed it first, the wizard's largest word was a command that fails on
        // launch. A head whose provider is missing is skipped, exactly as status skips it.
        val heads = topology.heads.entries.mapNotNull { (key, head) ->
            val provider = topology.providers[head.provider] ?: return@mapNotNull null
            Triple(key, head, credentialed(key, provider))
        }
        val ready = heads.filter { it.third }.map { (key, head, _) -> selector(key, head) }
        val pending = heads.filterNot { it.third }.map { "splice login ${it.first}" }
        println()
        println()
        println("      " + palette.strong + palette.signal + launch + palette.off)
        println()
        println()
        if (ready.isNotEmpty()) {
            println("  " + palette.paint(palette.quiet, "in claude     ") + ready.joinToString("   "))
        }
        for (login in pending) {
            println("  " + palette.paint(palette.quiet, "needs login   ") + palette.paint(palette.signal, login))
        }
        println()
        // The redesign moved the launch command to the top and dropped the labels. The dashboard
        // left with the console on Oct 7, 2026.
        // The console came back after that, and nothing on a new install named it (Nov 11, 2026): the installer, this
        // screen and `splice status` all left a person to find `splice console` in the usage line.
        verbs(
            LAUNCH_CLAUDE to "Claude Code on Splice (leaves default claude untouched)",
            "splice launch claude-desktop" to "Claude Desktop side-by-side on Splice",
            "splice console" to "accounts, models and usage, in a browser",
            "splice status" to "see what's running",
            "splice doctor" to "anything wrong prints its fix",
            "splice setup --update" to "set up what a new release adds",
        )
        // Nov 12, 2026: the one request the daemon makes that nobody asked it for is said here, with its host and
        // the line that turns it off.
        println()
        note("Once an hour splice fetches its price table from $PRICE_REFRESH_HOST:")
        note("one anonymous request. refresh_prices = false under [daemon] turns it off.")
        // Room before the frame's outro, which renders at column 0 and would otherwise read as one
        // more row of this list.
        println()
    }

    /** What to type in `claude` for [head]'s models: `/model <its pinned selector>`, or its key with none pinned. */
    private fun selector(key: String, head: HeadConfig): String =
        head.pinnedModel.takeIf { it.isNotBlank() }?.let { "/model ${head.discoveryPrefix}$it" } ?: key

    /** A head that can launch as-is: the client's own login, or a credential splice can find. */
    private fun credentialed(key: String, provider: ProviderConfig): Boolean =
        AuthKindRegistry.from(provider.auth.kind) == AuthKind.Client ||
            signIn.credentialConfigured(key, provider, env)

    /** A sentence under the commands, in the quiet tone. */
    private fun note(text: String) {
        println("  " + palette.paint(palette.quiet, text))
    }

    /** Each command and what it is for, the commands padded to the longest one plus a gap, so the descriptions form a
     *  column. Until Nov 23, 2026 the width was a fixed 18, sized for "splice console", and every longer command
     *  ("splice launch claude-desktop" is 28) ran into its description. */
    private fun verbs(vararg rows: Pair<String, String>) {
        val width = rows.maxOf { it.first.length } + VERB_GAP
        for ((command, purpose) in rows) {
            println("  " + palette.paint(palette.signal, command.padEnd(width)) + palette.paint(palette.quiet, purpose))
        }
    }
}

/** The spaces between the longest command in the closing block and its description. */
private const val VERB_GAP = 3

/** The command that starts Claude Code on splice's door, beside the person's own `claude`. */
internal const val LAUNCH_CLAUDE = "splice launch claude"
