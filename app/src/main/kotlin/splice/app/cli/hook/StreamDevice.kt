// NEW: terminal detection for a process's own file descriptors (split from HarnessHook.kt so HarnessHook stays focused
// on hook payload reading and exit-code policy).
package splice.app.cli.hook

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Whether one of this process's streams is a terminal. A hook's stdin is a pipe, so this is how a command tells a
 *  hook from an operator without also treating a piped stdout as one (System.console() is null then, and reading a real
 *  terminal would wait until the operator typed Ctrl-D); and how `splice desktop credential` tells the program that runs
 *  it from a person, and `splice desktop on` a person it can ask from a script. */
internal fun interface StreamTerminal {
    fun isTerminal(): Boolean
}

/** The file descriptors of stdin and stdout, as POSIX numbers them for every process. */
internal const val STDIN_FD = 0

// why: POSIX numbers a process's standard output 1, as it numbers its standard input 0.
internal const val STDOUT_FD = 1

/** `/proc/self/fd/<n>` names a terminal; anything else, including a pipe, does not. */
internal object StdinKind {
    fun terminal(link: String): Boolean = link.startsWith("/dev/pts/") || link.startsWith("/dev/tty")
}

/** The production [StreamTerminal], for file descriptor [fd] (stdin by default). When the kernel will not say, a
 *  missing console is not a terminal: a hook has no console, and treating it as one would skip the payload and exit 2. */
internal class StreamDevice(fd: Int = STDIN_FD) : StreamTerminal {
    private val stream = Path.of("/proc/self/fd/$fd")

    override fun isTerminal(): Boolean {
        val link = try {
            Files.readSymbolicLink(stream).toString()
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
        return if (link == null) System.console() != null else StdinKind.terminal(link)
    }
}
