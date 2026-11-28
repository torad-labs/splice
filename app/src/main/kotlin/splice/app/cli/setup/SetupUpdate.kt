// NEW: `splice setup --update`: the checkup of an install that is already set up, for a person whose splice just
// upgraded (`splice upgrade` points here from its "What's new / action needed" list). It asks nothing about the plans
// and heads already configured, and offers only what this release can do that the install does not do yet: the
// side-by-side launchers not installed, all of Claude Desktop on splice, MCP import and harness sync, the Modes that
// are off, and automatic updates. What only root can change (Claude Code's managed settings) and what needs a
// program splice does not install (systemd for the MCP hub) is said with what to do. It lays the Antigravity shim
// where it is missing, as install.sh would, and ends on `splice doctor`, as setup does.
package splice.app.cli.setup

import splice.app.capability.CapabilityGaps
import splice.app.capability.CodexHooksReading
import splice.app.capability.DesktopFacts
import splice.app.capability.Gap
import splice.app.capability.GapKind
import splice.app.cli.desktop.APP_COSTS
import splice.app.cli.desktop.DesktopCommand
import splice.app.desktop.setup.DesktopMode
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_COMMAND
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.launch.install.GeminiShim
import splice.launch.install.GeminiShimLaid
import splice.launch.install.InstallCommand
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

internal class SetupUpdate(
    private val prompts: SetupPrompts,
    private val env: EnvReader,
    private val effects: SetupEffects,
    private val detect: SetupProbe,
    private val desktopOn: DesktopOn = DesktopOn { DesktopCommand(env).run(listOf("on", "--yes")) == 0 },
    private val modes: SetupModes = SetupModes(prompts, env),
) {
    private val frame = prompts.frame
    private val sync = SetupMcpSync(env)
    private val codexHooks = SetupCodexHooks(prompts)
    private val autoUpdate = SetupAutoUpdate(prompts, env)

    /** False only when there is no install to update; every offer declined is still a good run. */
    suspend fun run(): Boolean {
        frame.intro("splice setup --update")
        val path = TopologyLoader.configPath(env)
        if (!Files.exists(path)) {
            frame.step("splice is not set up here yet, so there is nothing to update")
            frame.outro("Run `splice setup` first.")
            return false
        }
        // The shim is upkeep, laid before reading gaps so a newly laid shim is never listed as missing.
        val shimFailed = !shim()
        val facts = detect().capabilities
        val allGaps = facts?.let { CapabilityGaps(it, claudeWrapped()).all() }.orEmpty()
        val gaps = if (shimFailed) allGaps else allGaps.filterNot { it.kind == GapKind.GEMINI_SHIM }
        val hooksReading = facts?.harness?.takeIf { it.codex != null }?.codexHooks
        val interactive = prompts.hasConsole()
        val listed = if (interactive) {
            asked(path, facts?.desktop, hooksReading, gaps, shimFailed)
        } else {
            unattended(gaps, shimFailed)
        }
        val shimCounted = !interactive && gaps.any { it.kind == GapKind.GEMINI_SHIM }
        val open = listed + if (shimFailed && !shimCounted) 1 else 0
        effects.checkup(env)
        footer(open)
        return true
    }

    /** Each offer in turn, then what no offer here closes; "nothing new" (in what splice checked) only when nothing was
     *  offered, left or failed. Returns how many open or skipped items were reported. */
    private suspend fun asked(
        path: Path,
        desktop: DesktopFacts?,
        hooksReading: CodexHooksReading?,
        gaps: List<Gap>,
        shimFailed: Boolean,
    ): Int {
        val kinds = gaps.map { it.kind }.toSet()
        val launcherOutcome = launchers(desktop)
        val desktopOutcome = desktop(desktop)
        val outcomes = listOf(
            launcherOutcome,
            desktopOutcome,
            mcp(GapKind.MCP_SYNC in kinds),
            codexPeerHooks(hooksReading),
            modes(path),
            autoUpdate(path),
        )
        val currentGaps = if (desktopOutcome.offer == Offer.DONE) {
            detect().capabilities?.let { CapabilityGaps(it, claudeWrapped()).all() } ?: gaps
        } else {
            gaps
        }
        val left = currentGaps.filter { it.kind in SAID_ONLY }
        left.forEach { frame.step(it.text) }
        val clean = outcomes.all { it.offer == Offer.NOTHING } && left.isEmpty()
        when {
            clean && !shimFailed -> frame.step(NOTHING_NEW)
            outcomes.none { it.offer == Offer.DONE } ->
                frame.step("Nothing changed; `splice setup --update` asks again any time.")
        }
        return left.size + outcomes.sumOf { it.open }
    }

    /** With no terminal nothing can be asked: every gap is said, with where to set it up. Returns how many open items
     *  were listed. */
    private fun unattended(gaps: List<Gap>, shimFailed: Boolean): Int {
        if (gaps.isEmpty()) {
            if (!shimFailed) frame.step(NOTHING_NEW)
            return 0
        }
        gaps.forEach { frame.step(it.text) }
        frame.step("Run `splice setup --update` in a terminal to set these up.")
        return gaps.size
    }

    /** The closing line: green all-clear only when no open item was listed, yellow otherwise. */
    private fun footer(open: Int) = when (open) {
        0 -> frame.outro("Up to date.")
        1 -> frame.outroWarn("1 open item listed above.")
        else -> frame.outroWarn("$open open items listed above.")
    }

    /** The Antigravity shim, laid where it is missing or stale, and said when it was. False when the write failed. */
    private fun shim(): Boolean = when (val laid = GeminiShim(env).lay()) {
        is GeminiShimLaid.Written -> true.also { frame.step("Laid the Antigravity shim at ${laid.path}") }
        is GeminiShimLaid.Failed -> false.also { frame.step("Could not lay the Antigravity shim (${laid.why})") }
        GeminiShimLaid.Current -> true
    }

    /** The side-by-side launchers not installed yet. */
    private fun launchers(desktop: DesktopFacts?): Outcome {
        val launchers = effects.launchers()
        if (!launchers.hasMissing(desktop)) return Outcome(Offer.NOTHING)
        val choice = launchers.offer(prompts, desktop, onlyNew = true)
        val chosen = choice?.selectedCount ?: 0
        return if (choice == null || chosen == 0) {
            Outcome(Offer.DECLINED)
        } else {
            val skipped = launchers.apply(choice, frame)
            Outcome(if (skipped < chosen) Offer.DONE else Offer.DECLINED, open = skipped)
        }
    }

    /** All of Claude Desktop on splice, offered where Desktop is installed and is off splice or on the older
     *  Code-tab-only door. */
    private suspend fun desktop(desktop: DesktopFacts?): Outcome {
        val mode = if (desktop?.program != null) desktop.mode else null
        if (mode != DesktopMode.OFF && mode != DesktopMode.STANDARD) return Outcome(Offer.NOTHING)
        val older = mode == DesktopMode.STANDARD
        val question = if (older) {
            "Move Claude Desktop from the older Code-tab door to all of Desktop on splice (`splice desktop on`)?"
        } else {
            "Put all of Claude Desktop (Chat, Cowork, Code) on splice now (`splice desktop on`)?"
        }
        if (!frame.confirm(question, older) || !confirmDesktopCosts()) return Outcome(Offer.DECLINED)
        val on = Cancellables.runCatchingBestEffort { desktopOn() }.getOrDefault(false)
        return if (on) Outcome(Offer.DONE) else Outcome(Offer.DECLINED, open = 1)
    }

    /** Shows what putting Desktop on splice costs and confirms before running `splice desktop on --yes`. */
    private fun confirmDesktopCosts(): Boolean {
        frame.step(APP_COSTS)
        val accepted = frame.confirm("Put Claude Desktop on splice?", false)
        if (!accepted) frame.step("Nothing was changed.")
        return accepted
    }

    /** MCP import and harness sync, which offers itself where there is something to bring in or sync; [unsynced]:
     *  agy or codex is installed and not synced, so declining leaves something undone. */
    private suspend fun mcp(unsynced: Boolean): Outcome {
        val choice = sync.offer(prompts) ?: return Outcome(Offer.DECLINED)
        if (!choice.active) return Outcome(if (unsynced) Offer.DECLINED else Offer.NOTHING)
        val result = sync.apply(choice, frame)
        return Outcome(if (result.done > 0) Offer.DONE else Offer.DECLINED, open = result.failed)
    }

    private fun codexPeerHooks(reading: CodexHooksReading?): Outcome = when (codexHooks.offerUpdate(reading)) {
        SetupCodexHooks.UpdateOutcome.NOTHING -> Outcome(Offer.NOTHING)
        SetupCodexHooks.UpdateOutcome.DECLINED -> Outcome(Offer.DECLINED)
        SetupCodexHooks.UpdateOutcome.DONE -> Outcome(Offer.DONE)
        SetupCodexHooks.UpdateOutcome.FAILED -> Outcome(Offer.DECLINED, open = 1)
    }

    /** The Modes that are off; a running daemon is restarted onto any turned on, so the doctor and the next session
     *  see them. */
    private fun modes(path: Path): Outcome {
        val on = modes.offer(path) ?: return Outcome(Offer.NOTHING)
        if (on.isEmpty()) return Outcome(Offer.DECLINED)
        val failed = modes.save(path, on)
        val saved = failed < on.size
        val restartFailed = saved && !restartDaemon()
        val open = failed + if (restartFailed) 1 else 0
        return Outcome(if (saved) Offer.DONE else Offer.DECLINED, open = open)
    }

    /** Restarts a running daemon onto newly enabled modes; says so and returns false when the restart fails. */
    private fun restartDaemon(): Boolean {
        val ok = Cancellables.runCatchingBestEffort { effects.restart() }.getOrDefault(false)
        if (!ok) frame.step("Could not restart the daemon; restart splice for newly enabled modes to take effect.")
        return ok
    }

    private fun autoUpdate(path: Path): Outcome {
        if (!autoUpdate.askable(path)) return Outcome(Offer.NOTHING)
        if (!autoUpdate.offer(path)) return Outcome(Offer.DECLINED)
        return if (autoUpdate.save(path, true)) Outcome(Offer.DONE) else Outcome(Offer.DECLINED, open = 1)
    }

    /** Whether plain `claude` runs splice's launch shim: an older `splice setup` wrapped it. */
    private fun claudeWrapped(): Boolean = Cancellables.runCatchingBestEffort {
        val quiet = TerminalOutput { }
        InstallCommand(quiet, quiet).runsShim(CLAUDE_COMMAND, env)
    }.getOrDefault(false)

    /** What one offer came to: nothing to offer, offered and left as it was, or done. */
    private enum class Offer { NOTHING, DECLINED, DONE }

    /** One offer's status and how many requested items under it were skipped or failed. */
    private data class Outcome(val offer: Offer, val open: Int = 0)
}

// What an update says where it found nothing to set up. It speaks only of what splice checked: some places, such as
// those Claude Code takes managed settings from, splice names without reading, and the checkup after it names them.
private const val NOTHING_NEW = "Nothing new to set up in what splice checked."

// The gaps `setup --update` can only say: root writes Claude Code's managed settings, and systemd is not splice's to
// install. Every other gap has its offer above.
private val SAID_ONLY = setOf(GapKind.MANAGED_SETTINGS, GapKind.HUB_SYSTEMD)
