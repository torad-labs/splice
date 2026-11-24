// NEW: opens Splice Console's Accounts page for bare `splice login`, kept in its own file so LoginCommand stays
// focused on head/CLI sign-in dispatch without importing splice.app.cli.status (concentration).
package splice.app.cli.auth

import splice.app.cli.status.ConsoleCommand
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

/** Opens Splice Console's Accounts page (`splice console`), injected so bare `splice login` is testable headless. */
internal fun interface ConsoleAccountsOpen {
    operator fun invoke(openBrowser: Boolean): Boolean
}

/** Production opener delegating to [ConsoleCommand.open], falling back to printing the URL when headless on Linux. */
internal class DefaultConsoleAccountsOpen(
    private val output: TerminalOutput,
    private val env: EnvReader = EnvReader(System::getenv),
) : ConsoleAccountsOpen {
    override fun invoke(openBrowser: Boolean): Boolean =
        ConsoleCommand(out = output).open(openBrowser = openBrowser && hasDisplay())

    private fun hasDisplay(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("nux")) return true
        return !env("DISPLAY").isNullOrBlank() || !env("WAYLAND_DISPLAY").isNullOrBlank()
    }
}
