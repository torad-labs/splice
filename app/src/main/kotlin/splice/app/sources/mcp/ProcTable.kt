// NEW: Oct 12, 2026 — the machine's process table as the MCP memory measurement reads it (docs/specs/mcp-hub.md,
// step 5's measured savings). Read-only, and only these files under /proc/<pid>: stat (the parent), exe (which
// program), cmdline (the command words, to tell which server a copy runs), cgroup (which slice and scope it runs in),
// smaps_rollup (PSS) and status (RSS).
// Never environ. The command words are compared and dropped: nothing here keeps, logs or serves them, because an
// argument can carry a secret.
package splice.app.sources.mcp

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

// why: /proc reports memory in kB (KiB); the route answers bytes.
private const val KIB = 1024L

/** One look at the process table: every live process and its parent, read once per measurement. */
internal class ProcTable(private val root: Path = Path.of("/proc")) {
    private val parents: Map<Long, Long> = readParents()
    private val children: Map<Long, List<Long>> = parents.entries.groupBy({ it.value }, { it.key })

    val pids: Set<Long> get() = parents.keys

    fun childrenOf(pid: Long): List<Long> = children[pid].orEmpty()

    fun parentOf(pid: Long): Long? = parents[pid]

    /** Whether [pid] runs in the systemd slice named [slice]: its cgroup path (cgroup v2, `0::/...`) holds the slice
     *  as one of its segments. A process that ended or can't be read is in none. */
    fun inSlice(pid: Long, slice: String): Boolean = try {
        Files.readAllLines(root.resolve("$pid/cgroup")).any { line -> slice in line.substringAfterLast(':').split('/') }
    } catch (_: IOException) {
        false
    }

    /** [pid] and every process under it: a server with the wrapper layers (npm exec, sh -c) that started it. */
    fun subtree(pid: Long): List<Long> {
        val out = ArrayList<Long>()
        val stack = ArrayDeque(listOf(pid))
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            out += next
            childrenOf(next).forEach(stack::addLast)
        }
        return out
    }

    /** The program a process runs, or null where it has ended or belongs to another user. */
    fun exe(pid: Long): String? = try {
        Files.readSymbolicLink(root.resolve("$pid/exe")).toString()
    } catch (_: IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    /** The command words joined by spaces, for matching against a declared server and nothing else. */
    fun words(pid: Long): String = try {
        String(Files.readAllBytes(root.resolve("$pid/cmdline")), Charsets.UTF_8).replace('\u0000', ' ').trim()
    } catch (_: IOException) {
        ""
    }

    /** What a process holds: its PSS, its share of the pages it shares, or its RSS where the rollup can't be read. */
    fun memory(pid: Long): ProcMemory? =
        kib(pid, "smaps_rollup", "Pss:")?.let { ProcMemory(it * KIB, pss = true) }
            ?: rss(pid)?.let { ProcMemory(it, pss = false) }

    /** Everything a process has resident, shared pages counted whole: what one copy costs when it runs alone. */
    fun rss(pid: Long): Long? = kib(pid, "status", "VmRSS:")?.times(KIB)

    private fun kib(pid: Long, file: String, key: String): Long? = try {
        Files.readAllLines(root.resolve("$pid/$file")).firstOrNull { it.startsWith(key) }
            ?.removePrefix(key)?.trim()?.substringBefore(' ')?.toLongOrNull()
    } catch (_: IOException) {
        null
    }

    private fun readParents(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        Files.newDirectoryStream(root) { it.fileName.toString().all(Char::isDigit) }.use { dir ->
            for (entry in dir) parentOf(entry)?.let { out[entry.fileName.toString().toLong()] = it }
        }
        return out
    }

    /** stat reads `pid (comm) state ppid ...`, and a comm can hold spaces and parentheses, so the parent is read
     *  after the last `)`. A process that ended between the listing and the read has none. */
    private fun parentOf(dir: Path): Long? = try {
        Files.readString(dir.resolve("stat")).substringAfterLast(')').trim().split(' ').getOrNull(1)?.toLongOrNull()
    } catch (_: IOException) {
        null
    }
}

/** A process's memory in bytes, and whether it is PSS ([pss]) or the RSS read where PSS could not be. */
internal data class ProcMemory(val bytes: Long, val pss: Boolean)
