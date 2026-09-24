// NEW: compose `splice add` and `splice add-model` with the login flow, the wrapper linker, the daemon
// lifecycle and the terminal (LAYOUT-01). The verbs moved to features/configuration; the console
// prompter stays here, beside the stdin it reads.
package splice.app

import splice.app.cli.AdminSupport
import splice.app.cli.auth.LoginCommand
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
            install = WrapperInstall { key, env -> InstallWiring.command().install(key, env) },
            restart = restart,
            daemonUp = DaemonUpProbe { port -> AdminSupport.daemonUp(port) },
            prompt = ConsolePrompter(),
        ),
    )

    fun addModel(): AddModelsVerb = AddModelsVerb(
        SelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out),
        MultiSelectPrompt(KeyReader(System.`in`), TerminalMode(), System.out),
    )
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
