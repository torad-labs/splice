// NEW: compose `splice add` and `splice add-model` with the login flow, the wrapper linker, the daemon
// lifecycle and the terminal (LAYOUT-01). The verbs moved to features/configuration; the console
// prompter stays here, beside the stdin it reads.
package splice.app

import splice.app.auth.AddSignInSessions
import splice.app.cli.AdminSupport
import splice.app.cli.auth.LoginCommand
import splice.configuration.add.AddConsole
import splice.configuration.add.AddLiveResult
import splice.configuration.add.AddLiveTurn
import splice.configuration.add.AddLogin
import splice.configuration.add.AddModelsVerb
import splice.configuration.add.AddPorts
import splice.configuration.add.AddPrompter
import splice.configuration.add.AddVerb
import splice.configuration.add.DaemonRestart
import splice.configuration.add.DaemonUpProbe
import splice.configuration.add.WrapperInstall
import splice.core.config.InstallPaths
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
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// A connection check expects one word; cap the bytes read without retaining provider output in memory.
private const val LIVE_OUTPUT_BYTES = 4096

// Allow the killed foreground check to exit without making its cleanup an unbounded wait.
private const val LIVE_EXIT_WAIT_SECONDS = 2L

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
            liveTurn = CommandLiveTurn(),
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

/** Exercises the head's installed command, including client-auth and OAuth launch setup.
 *  Raw child output stays out of the add receipt because it can contain provider or login details. */
internal class CommandLiveTurn(private val timeoutMs: Long = 120_000L) : AddLiveTurn {
    override fun invoke(command: String, env: EnvReader): AddLiveResult =
        check(InstallPaths(envReader = env).binDir.resolve(command))

    internal fun check(wrapper: Path): AddLiveResult {
        // A private file avoids waiting on pipes inherited by background startup children.
        val output = Files.createTempFile("splice-add-check-", ".out")
        var process: Process? = null
        return try {
            process = ProcessBuilder(arguments(wrapper))
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            val problem = when {
                !process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) -> {
                    process.destroyForcibly()
                    process.waitFor(LIVE_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
                    "the head did not answer before the check's time limit"
                }
                process.exitValue() != 0 ->
                    "the head's command exited ${process.exitValue()}; run splice doctor for its checks"
                !answered(output) -> "the command finished but did not return the requested answer"
                else -> null
            }
            AddLiveResult(problem == null, problem ?: "the head answered the check turn")
        } catch (_: IOException) {
            AddLiveResult(false, "the command or its answer could not be read; check its installation")
        } finally {
            // Only this check's foreground client, never the daemon it may have cold-started.
            if (process?.isAlive == true) process.destroyForcibly()
            Cancellables.discard(
                Cancellables.runCatchingCleanup { Files.deleteIfExists(output) },
                "private check output is removed after every attempt; cleanup must not hide its diagnosis",
            )
        }
    }

    private fun answered(output: Path): Boolean = Files.newInputStream(output).use { stream ->
        val bytes = stream.readNBytes(LIVE_OUTPUT_BYTES + 1)
        val answer = String(bytes, StandardCharsets.UTF_8).trim()
        bytes.size <= LIVE_OUTPUT_BYTES && answer.equals("pong", ignoreCase = true)
    }

    private fun arguments(wrapper: Path): List<String> = listOf(
        wrapper.toString(), "-p", "Reply with the single word pong.",
        "--tools", "", "--strict-mcp-config", "--mcp-config", "{}", "--output-format", "text",
        "--no-session-persistence", "--system-prompt", "This is a connection check. Reply only to the prompt.",
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
