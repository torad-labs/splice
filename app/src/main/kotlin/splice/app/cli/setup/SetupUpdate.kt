// NEW: `splice setup --update`: the checkup of an install that is already set up, for a person whose splice just
// upgraded (`splice upgrade` points here from its "What's new / action needed" list). It asks nothing about the plans
// and heads already configured, and offers only what this release can do that the install does not do yet: the
// side-by-side launchers not installed, all of Claude Desktop on splice, MCP import and harness sync, the Modes that
// are off, and automatic updates. What only root can change (Claude Code's managed processWrapper) and what needs a
// program splice does not install (systemd for the MCP hub) is said with what to do. It lays the Antigravity shim
// where it is missing, as install.sh would, and ends on `splice doctor`, as setup does.
package splice.app.cli.setup

import splice.app.capability.CapabilityGaps
import splice.app.capability.DesktopFacts
import splice.app.capability.Gap
import splice.app.capability.GapKind
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

/** `splice desktop on`, as `setup --update` runs it: true when Desktop was put on splice. */
internal fun interface DesktopOn {
    suspend operator fun invoke(): Boolean
}

internal class SetupUpdate(
    private val prompts: SetupPrompts,
    private val env: EnvReader,
    private val effects: SetupEffects,
    private val detect: SetupProbe,
    private val desktopOn: DesktopOn = DesktopOn { DesktopCommand(env).run(listOf("on")) == 0 },
) {
    private val frame = prompts.frame
    private val sync = SetupMcpSync(env)
    private val modes = SetupModes(prompts)
    private val autoUpdate = SetupAutoUpdate(prompts)

    /** False only when there is no install to update; every offer declined is still a good run. */
    suspend fun run(): Boolean {
        frame.intro("splice setup --update")
        val path = TopologyLoader.configPath(env)
        if (!Files.exists(path)) {
            frame.step("splice is not set up here yet, so there is nothing to update")
            frame.outro("Run `splice setup` first.")
            return false
        }
        val facts = detect().capabilities
        val gaps = facts?.let { CapabilityGaps(it, claudeWrapped()).all() }.orEmpty()
        // The shim is upkeep, said when laid, and no offer: "nothing new" is about what the person is asked.
        shim()
        val done = listOf(launchers(facts?.desktop), desktop(facts?.desktop)).count { it } +
            listOf(mcp(), modes(path), autoUpdate(path)).count { it }
        val said = said(gaps)
        if (done == 0 && !said) frame.step("Nothing new to set up: this install already has what this release adds.")
        effects.checkup(env)
        frame.outro("Up to date.")
        return true
    }

    /** The Antigravity shim, laid where it is missing or stale, and said when it was. */
    private fun shim() {
        when (val laid = GeminiShim(env).lay()) {
            is GeminiShimLaid.Written -> frame.step("Laid the Antigravity shim at ${laid.path}")
            is GeminiShimLaid.Failed -> frame.step("Could not lay the Antigravity shim (${laid.why})")
            GeminiShimLaid.Current -> Unit
        }
    }

    /** The side-by-side launchers not installed yet; true when one was. */
    private fun launchers(desktop: DesktopFacts?): Boolean {
        val launchers = effects.launchers()
        val choice = launchers.offer(prompts, desktop, onlyNew = true) ?: return false
        launchers.apply(choice, frame)
        return choice.cliShortcut || choice.desktopApp
    }

    /** All of Claude Desktop on splice, offered where Desktop is installed and is off splice or on the older
     *  Code-tab-only door. */
    private suspend fun desktop(desktop: DesktopFacts?): Boolean {
        val mode = desktop?.takeIf { it.program != null && prompts.hasConsole() }?.mode
        if (mode != DesktopMode.OFF && mode != DesktopMode.STANDARD) return false
        val older = mode == DesktopMode.STANDARD
        val question = if (older) {
            "Move Claude Desktop from the older Code-tab door to all of Desktop on splice (`splice desktop on`)?"
        } else {
            "Put all of Claude Desktop (Chat, Cowork, Code) on splice now (`splice desktop on`)?"
        }
        if (!frame.confirm(question, older)) return false
        return Cancellables.runCatchingBestEffort { desktopOn() }.getOrDefault(false)
    }

    /** MCP import and harness sync, which offers itself only where there is something to bring in or sync. */
    private suspend fun mcp(): Boolean {
        val choice = sync.offer(prompts) ?: return false
        sync.apply(choice, frame)
        return choice.active
    }

    private fun modes(path: Path): Boolean {
        val on = modes.offer(path)
        modes.save(path, on)
        return on.isNotEmpty()
    }

    private fun autoUpdate(path: Path): Boolean {
        val on = autoUpdate.offer(path)
        autoUpdate.save(path, on)
        return on
    }

    /** The gaps no offer here closes, each said with what to do; true when any was. */
    private fun said(gaps: List<Gap>): Boolean {
        val left = gaps.filter { it.kind in SAID_ONLY }
        left.forEach { frame.step(it.text) }
        return left.isNotEmpty()
    }

    /** Whether plain `claude` runs splice's launch shim: an older `splice setup` wrapped it. */
    private fun claudeWrapped(): Boolean = Cancellables.runCatchingBestEffort {
        val quiet = TerminalOutput { }
        InstallCommand(quiet, quiet).runsShim(CLAUDE_COMMAND, env)
    }.getOrDefault(false)
}

// The gaps `setup --update` can only say: root writes Claude Code's managed settings, and systemd is not splice's to
// install. Every other gap has its offer above.
private val SAID_ONLY = setOf(GapKind.PROCESS_WRAPPER, GapKind.HUB_SYSTEMD)
