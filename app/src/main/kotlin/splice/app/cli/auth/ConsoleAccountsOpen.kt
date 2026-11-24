// NEW: opens Splice Console's Accounts page for bare `splice login`, kept in its own file so LoginCommand stays
// focused on head/CLI sign-in dispatch without importing splice.app.cli.status (concentration).
package splice.app.cli.auth

import splice.app.cli.status.ConsoleCommand
import splice.core.terminal.TerminalOutput

/** Opens Splice Console's Accounts page (`splice console`), injected so bare `splice login` is testable headless. */
internal fun interface ConsoleAccountsOpen {
    operator fun invoke(): Boolean
}

/** Production opener delegating to [ConsoleCommand.open]. */
internal class DefaultConsoleAccountsOpen(private val output: TerminalOutput) : ConsoleAccountsOpen {
    override fun invoke(): Boolean = ConsoleCommand(out = output).open()
}
