// NEW: side-by-side-launchers. Replaces automatic default `claude` wrapping in `splice setup`:
// default `claude` CLI and default Claude Desktop stay connected to the user's personal account.
// The wizard tells the user about `splice launch claude` and `splice launch claude-desktop` and
// optionally installs convenience shortcuts (`claude-splice` binary + OS Applications launcher).
package splice.app.cli.setup

import splice.app.capability.DesktopFacts
import splice.app.desktop.setup.AppLauncherInstall
import splice.app.desktop.setup.SIDE_BY_SIDE_APP_NAME
import splice.app.desktop.setup.SideBySideLaunchers
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_SPLICE_COMMAND
import splice.core.util.EnvReader
import splice.launch.install.InstallCommand
import splice.launch.install.InstallResult
import splice.terminal.MultiSelectOutcome
import splice.terminal.SelectOption
import splice.terminal.WizardFrame

/** Which optional side-by-side convenience launchers the operator opted to install during `splice setup`. */
internal data class LauncherChoice(
    val cliShortcut: Boolean = false,
    val desktopApp: Boolean = false,
)

internal class SetupLaunchers(
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
) {
    private val appLaunchers = SideBySideLaunchers(env, osName)

    /** Explains the two ways onto splice, side by side or all of Claude Desktop, and asks which optional shortcuts to
     *  install (none in non-TTY). The Claude (Splice) app launcher is offered only where Claude Desktop is installed,
     *  or where nothing looked ([desktop] null). With [onlyNew] (`splice setup --update`), a launcher already installed
     *  is not offered again; with nothing left to offer, nothing is asked. */
    fun offer(prompts: SetupPrompts, desktop: DesktopFacts? = null, onlyNew: Boolean = false): LauncherChoice? {
        val options = options(desktop, onlyNew)
        // An update with every launcher installed has nothing to say about them.
        if (onlyNew && options.isEmpty()) return LauncherChoice()
        intro(prompts, desktop == null || desktop.program != null)
        if (!prompts.hasConsole() || options.isEmpty()) return LauncherChoice()
        return when (val picked = prompts.pickLaunchers(options, emptySet())) {
            is MultiSelectOutcome.Chosen -> LauncherChoice(
                cliShortcut = OPT_CLI in picked.values,
                desktopApp = OPT_DESKTOP_APP in picked.values,
            )
            MultiSelectOutcome.Cancelled -> null
        }
    }

    /** The two ways onto splice, in words: side by side, or all of Claude Desktop where it is installed. */
    private fun intro(prompts: SetupPrompts, desktopFound: Boolean) {
        prompts.frame.step(
            "Default claude and Claude Desktop stay on your personal account. " +
                "Run `splice launch claude` or `splice launch claude-desktop` anytime to use splice beside them.",
        )
        prompts.frame.step(
            if (desktopFound) {
                "To put all of Claude Desktop (Chat, Cowork, Code) on splice instead: $DESKTOP_ON"
            } else {
                "Claude Desktop is not installed here, so its Claude (Splice) launcher is not offered."
            },
        )
    }

    /** The launchers to offer: `claude-splice`, and Claude (Splice) where Claude Desktop is installed (or nothing
     *  looked); with [onlyNew], neither one already installed. */
    private fun options(desktop: DesktopFacts?, onlyNew: Boolean): List<SelectOption<String>> {
        val cliDone = onlyNew && desktop?.sideBySideCli == true
        val appOffered = (desktop == null || desktop.program != null) && !(onlyNew && desktop?.sideBySideApp == true)
        return listOfNotNull(
            SelectOption(
                value = OPT_CLI,
                label = CLAUDE_SPLICE_COMMAND,
                hint = "CLI launcher in ~/.local/bin/$CLAUDE_SPLICE_COMMAND",
            ).takeUnless { cliDone },
            SelectOption(
                value = OPT_DESKTOP_APP,
                label = SIDE_BY_SIDE_APP_NAME,
                hint = "App launcher in ${appLaunchers.platformHint()}",
            ).takeIf { appOffered },
        )
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

// The full-app way onto splice, beside the side-by-side launchers.
private const val DESKTOP_ON = "splice desktop on"
