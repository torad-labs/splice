// NEW: CW-1 — raw-mode bracket every interactive widget sits on. stty against /dev/tty,
// restore in finally AND a shutdown hook; no-op when System.console is null.
package splice.app.cli.prompt

import java.io.File
import java.util.concurrent.Executors

/** stdout plus the process exit. A mode string and an error must not share a channel. */
internal data class SttyResult(val exit: Int, val stdout: String)

/** Runs an argv (stty …). Injected so tests never touch a real terminal. */
internal fun interface SttyCommand {
    fun run(args: List<String>): SttyResult
}

/** Production stty: stdin is /dev/tty so a piped JVM still talks to the real terminal. */
internal class UnixStty : SttyCommand {
    override fun run(args: List<String>): SttyResult {
        val process = ProcessBuilder(args)
            .redirectInput(File("/dev/tty"))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return SttyResult(exit, out)
    }
}

/**
 * Enters raw mode for the duration of [raw]. Restore is guaranteed on return, on exception,
 * and on SIGINT (shutdown hook). Non-TTY ([hasConsole] false) runs the block with zero stty.
 * A failed `stty -g` also runs the block unraw — never enter raw without a mode to restore.
 */
internal class TerminalMode(
    private val stty: SttyCommand = UnixStty(),
    private val hasConsole: ConsolePresence = ConsolePresence { System.console() != null },
    private val addHook: ShutdownHookAdd = ShutdownHookAdd { Runtime.getRuntime().addShutdownHook(it) },
    private val removeHook: ShutdownHookRemove = ShutdownHookRemove { Runtime.getRuntime().removeShutdownHook(it) },
) {
    fun <T> raw(block: RawBlock<T>): T {
        if (!hasConsole()) return block()
        val captured = stty.run(listOf("stty", "-g"))
        val saved = captured.stdout.trim()
        if (captured.exit != 0 || saved.isEmpty()) return block()
        // kt-no-raw-thread's own prescribed form: addShutdownHook demands the Thread TYPE and no
        // coroutine can stand in, so the platform factory owns thread group and priority.
        val hook = Executors.defaultThreadFactory().newThread { stty.run(listOf("stty", saved)) }
        addHook(hook)
        try {
            stty.run(listOf("stty", "-icanon", "-echo", "min", "1", "time", "0"))
            return block()
        } finally {
            try {
                stty.run(listOf("stty", saved))
            } finally {
                try {
                    removeHook(hook)
                } catch (_: IllegalStateException) {
                    // VM already shutting down — the hook itself is the restore.
                } catch (_: IllegalArgumentException) {
                    // Hook was not registered or already removed.
                }
            }
        }
    }
}
