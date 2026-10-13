// NEW: Mac session identity for sessions splice did not launch (Temple bundling queue, Oct 13). On Linux, /proc gives
// a plain `claude` session three facts: its pid domain and kernel start (ProcPidIdentity), which tell a live session
// from a reused pid or another container's, and its environment (ProcessEnvironment), whose SPLICE marker tells a
// session splice never launched (DIRECT) from a launch nothing recorded, and whose CLAUDE_CONFIG_DIR names its config
// home. macOS has no /proc. This reads the same facts from /bin/ps, which needs no root for this user's processes.
//
// THE SPELLINGS ARE CLAUDE CODE'S OWN (2.1.296, read from its bundle on Oct 13): off Linux and Windows its pidDomain is
// the literal "linux", and its procStart is `ps -o lstart= -p <pid>` run with LC_ALL=C and TZ=UTC, trimmed. The
// lstart here is that command with that environment, so the two strings are equal for the same process.
//
// THE ENVIRONMENT comes from `ps -E -ww -o command=`, which prints the arguments and then the environment, joined by
// spaces. The arguments alone (`ps -ww -o command=`) are cut from its front, and the rest is split before each
// NAME= into the NUL-separated block /proc/<pid>/environ holds, so ProcessEnvironment scans it unchanged. A value
// holding a space followed by NAME= splits there; only an entry starting SPLICE= or CLAUDE_CONFIG_DIR= could then be
// misread. macOS hides the environment of a hardened binary that lacks the get-task-allow entitlement (Apple
// StackExchange 439943, Big Sur and later), and ps then prints the arguments alone: that is an empty environment,
// which ProcessEnvironment already reads as not read (UNKNOWN), never as DIRECT.
//
// A process's environment and start, as ps reports them, never change after it starts, so each is remembered by pid
// and ProcessHandle's start instant; a reused pid has another start and is read again.
//
// UNTESTED ON A MAC: written against ps(1) and the bundle above on a Linux machine. Off macOS every member answers
// null and nothing runs, so the Linux reads are unchanged.
package splice.sessions.registry

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Claude Code's pidDomain on every host but Linux, WSL and Windows. */
private const val DARWIN_PID_DOMAIN = "linux"

private const val PS = "/bin/ps"

// why: Claude Code gives its own lstart read one second; a ps that takes longer is treated as no answer.
private const val PS_TIMEOUT_MS = 1_000L

// why: well above the sessions one daemon lists at once; past it the least recently used pid is read again.
private const val REMEMBERED_PIDS = 1024

// why: the JDK's default load factor; LinkedHashMap takes access order only through the constructor that asks for it.
private const val PID_LOAD = 0.75f

internal class DarwinPs(
    /** Whether this host is macOS. Elsewhere every read answers null and no ps runs. */
    val here: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac"),
) {
    private val starts = ByBirth<String>()
    private val environs = ByBirth<ByteArray>()
    private val beforeName = Regex(" (?=[A-Za-z_][A-Za-z0-9_]*=)")

    /** Claude Code's pidDomain on macOS, or null off macOS. */
    fun domain(): String? = DARWIN_PID_DOMAIN.takeIf { here }

    /** [pid]'s start as Claude Code writes procStart on macOS, or null off macOS or when ps cannot say. */
    fun lstart(pid: Long): String? {
        if (!here) return null
        val born = bornAt(pid)
        starts[pid, born]?.let { return it }
        val read = ps(listOf("-o", "lstart=", "-p", pid.toString()))?.trim()?.takeIf { it.isNotEmpty() }
        if (read != null && stillBorn(pid, born)) starts.put(pid, born, read)
        return read
    }

    /** [pid]'s environment as /proc/<pid>/environ spells it (NUL-separated NAME=value), empty when macOS hides it,
     *  or null off macOS or when ps cannot read the process. */
    fun environ(pid: Long): ByteArray? {
        if (!here) return null
        val born = bornAt(pid)
        environs[pid, born]?.let { return it }
        val read = readEnviron(pid)
        if (read != null && stillBorn(pid, born)) environs.put(pid, born, read)
        return read
    }

    private fun readEnviron(pid: Long): ByteArray? {
        val args = ps(listOf("-ww", "-o", "command=", "-p", pid.toString()))?.trimEnd()
        val all = ps(listOf("-E", "-ww", "-o", "command=", "-p", pid.toString()))?.trimEnd()
        val env = when {
            args == null || all == null -> null
            all.startsWith(args) -> all.substring(args.length).trimStart()
            else -> null
        } ?: return null
        return if (env.isEmpty()) ByteArray(0) else env.split(beforeName).joinToString("\u0000").toByteArray()
    }

    /** ps's output, or null when it could not run, failed, or took longer than Claude Code allows its own read. Its
     *  output is read to the end before the wait, so an environment longer than the pipe holds cannot stall it. */
    private fun ps(arguments: List<String>): String? = try {
        val builder = ProcessBuilder(listOf(PS) + arguments).redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment()["LC_ALL"] = "C"
        builder.environment()["TZ"] = "UTC"
        val process = builder.start()
        val out = process.inputStream.use { it.readBytes() }
        val exited = process.waitFor(PS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!exited) process.destroyForcibly()
        out.decodeToString().takeIf { exited && process.exitValue() == 0 }
    } catch (_: IOException) {
        null
    }

    /** Whether [pid] still has the start [born] it had before a read, so the read is that process's to keep. */
    private fun stillBorn(pid: Long, born: Long?): Boolean = born != null && born == bornAt(pid)

    private fun bornAt(pid: Long): Long? =
        ProcessHandle.of(pid).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(null)

    /** What was read for a pid, kept while the process holding that pid has the start it had: least recently used
     *  first out, safe across concurrent Sessions requests. */
    private class ByBirth<V : Any> {
        private data class Kept<V>(val born: Long, val value: V)

        private val entries = object : LinkedHashMap<Long, Kept<V>>(REMEMBERED_PIDS, PID_LOAD, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Kept<V>>?): Boolean =
                size > REMEMBERED_PIDS
        }

        operator fun get(pid: Long, born: Long?): V? =
            born?.let { at -> synchronized(entries) { entries[pid] }?.takeIf { it.born == at }?.value }

        fun put(pid: Long, born: Long?, value: V) {
            if (born != null) synchronized(entries) { entries[pid] = Kept(born, value) }
        }
    }
}
