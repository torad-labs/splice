// NEW: side-by-side-launchers. Replaces automatic default `claude` wrapping in `splice setup`:
// default `claude` CLI and default Claude Desktop stay connected to the user's personal account.
// The wizard tells the user about `splice launch claude` and `splice launch claude-desktop` and
// optionally installs convenience shortcuts (`claude-splice` binary + OS Applications launcher).
package splice.app.cli.setup

import splice.app.desktop.setup.AppLauncherInstall
import splice.app.desktop.setup.SIDE_BY_SIDE_APP_NAME
import splice.app.desktop.setup.SideBySideLaunchers
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_SPLICE_COMMAND
import splice.core.util.EnvReader
import splice.launch.install.InstallCommand
import splice.launch.install.InstallResult
import splice.terminal.MultiSelectOption
import splice.terminal.MultiSelectOutcome
import splice.terminal.WizardFrame

/** Which optional side-by-side convenience launchers the operator opted to install during `splice setup`. */
internal data class LauncherChoice(
    val cliShortcut: Boolean = false,
    val desktopApp: Boolean = false,
)

/** Prompts the operator to tick optional side-by-side launchers (multi-select). */
internal fun interface LauncherPicker {
    operator fun invoke(options: List<MultiSelectOption>, initial: Set<String>): MultiSelectOutcome
}

internal class SetupLaunchers(
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
) {
    private val appLaunchers = SideBySideLaunchers(env, osName)

    /** Explains side-by-side launching and asks which optional shortcuts to install (none in non-TTY). */
    fun offer(prompts: SetupPrompts): LauncherChoice? {
        prompts.frame.step(
            "Default claude and Claude Desktop stay on your personal account. " +
                "Run `splice launch claude` or `splice launch claude-desktop` anytime.",
        )
        if (!prompts.hasConsole()) return LauncherChoice()
        val options = listOf(
            MultiSelectOption(
                value = OPT_CLI,
                label = CLAUDE_SPLICE_COMMAND,
                hint = "CLI launcher in ~/.local/bin/$CLAUDE_SPLICE_COMMAND",
            ),
            MultiSelectOption(
                value = OPT_DESKTOP_APP,
                label = SIDE_BY_SIDE_APP_NAME,
                hint = "App launcher in ${appLaunchers.platformHint()}",
            ),
        )
        return when (val picked = prompts.pickLaunchers(options, emptySet())) {
            is MultiSelectOutcome.Chosen -> LauncherChoice(
                cliShortcut = OPT_CLI in picked.values,
                desktopApp = OPT_DESKTOP_APP in picked.values,
            )
            MultiSelectOutcome.Cancelled -> null
        }
    }

    /** Summary lines shown before confirmation. */
    fun summaryLines(choice: LauncherChoice): List<String> = buildList {
        add("Launch: splice launch claude · splice launch claude-desktop (default claude stays untouched)")
        if (choice.cliShortcut) add("CLI shortcut: $CLAUDE_SPLICE_COMMAND (~/.local/bin/$CLAUDE_SPLICE_COMMAND)")
        if (choice.desktopApp) add("App launcher: $SIDE_BY_SIDE_APP_NAME (${appLaunchers.platformHint()})")
    }

    /** Installs whichever optional shortcuts were chosen and reports each to [frame]. */
    fun apply(choice: LauncherChoice, frame: WizardFrame) {
        if (choice.cliShortcut) {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val res = InstallCommand(TerminalOutput { out += it }, TerminalOutput { err += it })
                .installShortcut(CLAUDE_SPLICE_COMMAND, env)
            when (res) {
                InstallResult.Linked -> frame.step("Installed CLI shortcut: $CLAUDE_SPLICE_COMMAND")
                InstallResult.Declined -> Unit
                is InstallResult.Refused -> frame.step("CLI shortcut skipped: ${res.sentence}")
            }
        }
        if (choice.desktopApp) {
            when (val res = appLaunchers.installDesktopApp()) {
                is AppLauncherInstall.Installed -> frame.step("Installed app launcher: ${res.path}")
                is AppLauncherInstall.Skipped -> frame.step("App launcher skipped: ${res.reason}")
            }
        }
    }
}

internal const val OPT_CLI: String = "claude-splice"
internal const val OPT_DESKTOP_APP: String = "claude-desktop-app"
