// NEW: compose `splice add` and `splice add-model` with the login flow, the wrapper linker, the daemon
// lifecycle and the terminal (LAYOUT-01). The verbs moved to features/configuration; the console
// prompter stays here, beside the stdin it reads.
package splice.app

import splice.app.auth.AddSignInSessions
import splice.app.cli.AdminSupport
import splice.app.cli.auth.LoginCommand
import splice.configuration.add.AddConsole
import splice.configuration.add.AddLogin
import splice.configuration.add.AddModelsVerb
import splice.configuration.add.AddPorts
import splice.configuration.add.AddPrompter
import splice.configuration.add.AddVerb
import splice.configuration.add.DaemonRestart
import splice.configuration.add.DaemonUpProbe
import splice.configuration.add.WrapperInstall
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.launch.install.InstallCommand
import splice.launch.install.InstallFailureText
import splice.terminal.KeyReader
import splice.terminal.MultiSelectPrompt
import splice.terminal.SelectPrompt
import splice.terminal.TerminalMode

internal object AddWiring {

    /** [restart] is the plain `splice restart` unless a caller owns the restart itself: setup adds its
     *  ticked heads and restarts once at the end. */
    fun add(restart: DaemonRestart = DaemonRestart { LifecycleWiring.restart() }): AddVerb = AddVerb(
        output = TerminalOutput(::println),
        errors = TerminalOutput(System.err::println),
        ports = AddPorts(
            login = AddLogin { key, provider, topology -> LoginCommand().runLoginFlow(key, provider, topology) },
            install = LinkerInstall(InstallWiring.command()),
            restart = restart,
            daemonUp = DaemonUpProbe { port -> AdminSupport.daemonUp(port) },
            prompt = ConsolePrompter(),
        ),
    )

    /** V4-220 item 3: `splice add` as the console runs it — its own sign-in sessions, the wrapper linked
     *  by the install verb with its lines in the daemon log, and this process's environment, the one
     *  the daemon's heads read their keys and files through. */
    fun console(log: LogSink): AddConsole {
        val lines = TerminalOutput { line -> log("[control] add: ${LogSafe.str(line)}\n") }
        return AddConsole(
            signIn = AddSignInSessions(),
            install = LinkerInstall(InstallCommand(lines, lines)),
            env = EnvReader(System::getenv),
            output = lines,
        )
    }

    fun addModel(): AddModelsVerb = AddModelsVerb(
        SelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out),
        MultiSelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out),
    )
}

/** V4-255: the install verb as the add's wrapper seam, for the CLI and the console alike. Its refusals
 *  print as the linker wrote them (InstallFailureText); anything else stays withheld. */
internal class LinkerInstall(private val install: InstallCommand) : WrapperInstall {
    override fun invoke(key: String, env: EnvReader): Boolean = install.install(key, env)

    override fun refusalText(failure: Throwable): String = InstallFailureText.render(failure)
}

/** Answers with the operator's line on a terminal, or the default without one. */
internal class ConsolePrompter : AddPrompter {
    override fun invoke(question: String, default: String): String {
        if (System.console() == null) return default
        print("$question ${if (default.isEmpty()) "" else "[$default] "}")
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): a stdin read that fails is indistinguishable from an empty line, and both mean 'take the default' — the next line states exactly that.
        val line = Cancellables.runCatchingCancellable { readlnOrNull()?.trim() }.getOrNull()
        return line?.ifEmpty { default } ?: default
    }
}
