// NEW: Oct 10, 2026 — whether a session's process is the client in front of the pane its launch recorded, so a
// message, an answer or a stop for that session is never typed into whatever else the pane holds now.
//
// WHY ANCESTRY. A launch from the person's shell runs under the pane's own process (the shell), and a launch
// that replaced the shell IS that process. Either way the session's process is the pane's process or descends
// from it; a pid the pane does not hold, including one reused by an unrelated program, does not.
//
// WHY THE FOREGROUND TOO. A session suspended to its shell (Ctrl-Z) is still under the pane, but what the pane
// types to is the shell. Linux says which process group owns the terminal's foreground in /proc/<pid>/stat; a
// system that does not show it answers unknown, and only ancestry is checked there.
package splice.tmux

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: the fields after the command's closing parenthesis in /proc/<pid>/stat, counted from its state (proc(5)):
// state, ppid, pgrp, session, tty_nr, tpgid.
private const val PGRP_FIELD = 2
private const val TPGID_FIELD = 5

// why: a process tree deeper than this is a loop or a misread, never a shell that started a session.
private const val MAX_DEPTH = 64

internal object ForegroundProcess {
    /** Whether [pid] is [root] or runs under it. */
    fun under(pid: Long, root: Long): Boolean {
        var at = ProcessHandle.of(pid).orElse(null)
        repeat(MAX_DEPTH) {
            val handle = at ?: return false
            if (handle.pid() == root) return true
            at = handle.parent().orElse(null)
        }
        return false
    }

    /** Whether [pid]'s process group owns its terminal's foreground, or null when this system does not say. */
    fun inFront(pid: Long, proc: Path = Path.of("/proc")): Boolean? {
        val fields = try {
            Files.readString(proc.resolve(pid.toString()).resolve("stat")).substringAfterLast(')').trim().split(' ')
        } catch (_: IOException) {
            return null
        }
        val group = fields.getOrNull(PGRP_FIELD)?.toLongOrNull()
        val front = fields.getOrNull(TPGID_FIELD)?.toLongOrNull()
        return if (group == null || front == null) null else group == front
    }
}
