// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). `splice desktop acp [claude args...]`: what
// Claude Desktop's launcher runs in place of `claude` for a `claude-antigravity--` model, through the `claude` shim
// install.sh lays in `~/.local/lib/splice/gemini/`. It runs the session in this folder on `agy acp` (DesktopAcp),
// speaking Desktop's stream-json on this process's own standard input and output, so nothing else may write to
// standard output; what goes wrong outside the conversation goes to standard error, which Desktop logs. It needs no
// daemon.
package splice.app.desktop.acp

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.provider.antigravity.DesktopAcp
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path

internal class DesktopAcpCommand(private val env: EnvReader, private val err: TerminalOutput) {
    /** Runs the session Desktop started with `claude` [args]; its exit code. */
    suspend fun run(args: List<String>): Int {
        val log = LogSink { err.line(it.trimEnd()) }
        val hooks = DesktopAcpHooks(env).build()
        val session = DesktopAcp(args, Path.of("").toAbsolutePath(), env, ProcessDispatchers().io(), log)
        return session.run(System.`in`, System.out, hooks)
    }
}
