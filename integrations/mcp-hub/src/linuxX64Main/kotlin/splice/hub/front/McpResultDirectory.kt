@file:OptIn(ExperimentalForeignApi::class)

package splice.hub.front

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.F_SETLKW
import platform.posix.F_WRLCK
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.flock
import platform.posix.fstat
import platform.posix.geteuid
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

/** Transactions share the JVM store's POSIX record lock, retention order and complete-entry budget. */
internal class McpResultDirectory(private val directory: String) {
    private val files = McpResultFileIo(directory)

    fun <T> locked(action: () -> T): T {
        ensurePrivate()
        val fd = open("$directory/.lock", O_CREAT or O_WRONLY or O_NOFOLLOW, MCP_RESULT_OWNER_RW)
        check(fd >= 0) { "cannot open raw MCP receipt lock" }
        return try {
            lock(fd)
            action()
        } finally {
            close(fd)
        }
    }

    fun read(name: String): String? = files.read(name)

    fun write(name: String, text: String) = files.write(name, text)

    fun prune(nowMs: Long) {
        val kept = names().mapNotNull { name ->
            when {
                abandoned(name) -> null.also { files.remove(name) }
                name.endsWith(".json") -> files.entry(name)
                else -> null
            }
        }.sortedWith(compareBy(McpResultEntry::atMs, McpResultEntry::name)).filter { entry ->
            val expired = nowMs - entry.atMs >= MCP_RESULT_AGE_MS
            if (expired) files.remove(entry.name)
            !expired
        }.toMutableList()
        var bytes = kept.sumOf(McpResultEntry::bytes)
        while (kept.size > MCP_RESULT_ITEMS || bytes > MCP_RESULT_TOTAL_BYTES) {
            val oldest = kept.removeAt(0)
            files.remove(oldest.name)
            bytes -= oldest.bytes
        }
    }

    private fun abandoned(name: String): Boolean =
        (name.startsWith(".secure") || name.startsWith(".mcp-result-")) && name.endsWith(".tmp")

    private fun names(): List<String> {
        val opened = opendir(directory)
        check(opened != null) { "cannot list raw MCP receipts" }
        return try {
            buildList {
                var entry = readdir(opened)
                while (entry != null) {
                    add(entry.pointed.d_name.toKString())
                    entry = readdir(opened)
                }
            }
        } finally {
            closedir(opened)
        }
    }

    private fun ensurePrivate() {
        check(mkdir(directory, MCP_RESULT_DIRECTORY_MODE) == 0 || errno == EEXIST) {
            "cannot create raw MCP receipts directory"
        }
        val fd = open(directory, O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
        check(fd >= 0) { "cannot open raw MCP receipts directory" }
        try {
            memScoped {
                val held = alloc<stat>()
                check(
                    fstat(fd, held.ptr) == 0 && held.st_uid == geteuid(),
                ) { "raw MCP receipts directory is not owned" }
                check(held.st_mode and MCP_RESULT_FOREIGN_BITS == 0u) { "raw MCP receipts directory is not private" }
            }
        } finally {
            close(fd)
        }
    }

    private fun lock(fd: Int) = memScoped {
        val record = alloc<flock>()
        record.l_type = F_WRLCK.convert()
        record.l_whence = SEEK_SET.convert()
        record.l_start = 0
        record.l_len = 0
        var result = fcntl(fd, F_SETLKW, record.ptr)
        while (result < 0 && errno == EINTR) result = fcntl(fd, F_SETLKW, record.ptr)
        check(result == 0) { "cannot lock raw MCP receipts" }
    }
}

private const val MCP_RESULT_DIRECTORY_MODE = 0x1C0u // 0700
private const val MCP_RESULT_AGE_MS = 24L * 60 * 60 * 1_000
private const val MCP_RESULT_TOTAL_BYTES = 256L * 1024 * 1024
private const val MCP_RESULT_ITEMS = 2_000
