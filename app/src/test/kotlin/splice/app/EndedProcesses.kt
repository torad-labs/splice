// Whether a process a test watched has ended. ProcessHandle.isAlive stays true for a process that has ended until its
// parent collects it, and an orphan's parent is whichever process adopted it. The user's systemd manager collects at
// once; a build wrapper that adopts its build's orphans, or a container's first process, may never, and then a process
// splice ended reads as alive for as long as that parent lives. These tests ask whether splice ended a process, which
// is so from the moment it stops running, so they ask here.
package splice.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object EndedProcesses {
    /** Whether [handle]'s process has stopped running: it is gone, or it ended and nothing has collected it yet, when
     *  it runs no code and holds no file. Where the kernel does not say (no /proc), what the JVM says. */
    fun ended(handle: ProcessHandle): Boolean {
        if (!handle.isAlive) return true
        val stat = try {
            Files.readString(Path.of("/proc/${handle.pid()}/stat"))
        } catch (_: IOException) {
            return !handle.isAlive
        }
        // The state is the first field after the command's name, which is in parentheses and may hold any character.
        return stat.substringAfterLast(')').trim().firstOrNull() in ENDED
    }

    // Z: ended and not collected. X: being removed.
    private val ENDED = setOf('Z', 'X')
}
