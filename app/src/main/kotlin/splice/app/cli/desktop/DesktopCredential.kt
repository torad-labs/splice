// NEW: desktop-full (docs/specs/desktop-full.md, df-credential). `splice desktop credential`: the program Claude Desktop
// runs as splice's configuration's inferenceCredentialHelper (DesktopPrograms lays the script that runs it). It
// prints the desktop key, alone on one line, to the program that runs it, and exits 0. Desktop runs it at launch and
// whenever its cached credential runs out, maybe before splice's daemon is up, so it reads the key from splice's state
// folder itself, minting one where there is none as the daemon would. On a terminal it prints no key: no person needs
// to read this one, and since `splice desktop on` writes Desktop's configuration itself, splice never shows the
// desktop key on a terminal.
package splice.app.cli.desktop

import splice.app.cli.hook.STDOUT_FD
import splice.app.cli.hook.StreamDevice
import splice.app.cli.hook.StreamTerminal
import splice.app.desktop.record.DesktopKey
import splice.core.terminal.TerminalOutput
import java.nio.file.Path

internal class DesktopCredential(
    private val out: TerminalOutput,
    private val err: TerminalOutput,
    private val stdout: StreamTerminal = StreamDevice(STDOUT_FD),
) {
    /** The key in [keyFile] printed for the program that runs this, or exit 1 with no key on a terminal. */
    fun run(keyFile: Path): Int {
        if (stdout.isTerminal()) {
            err.line(ON_TERMINAL)
            return 1
        }
        val key = DesktopKey(keyFile) { line -> err.line(line.trimEnd()) }
        out.line(key.get())
        return 0
    }
}

private const val ON_TERMINAL = "splice desktop credential: this is Claude Desktop's credential helper; it " +
    "prints the desktop key only to the program that runs it"
