@file:OptIn(ExperimentalForeignApi::class)

package splice.hub.front

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.geteuid
import platform.posix.getpid
import platform.posix.lstat
import platform.posix.open
import platform.posix.read
import platform.posix.rename
import platform.posix.stat
import platform.posix.unlink
import platform.posix.write

/** Whole-file I/O is private from creation, refuses symlinks and never publishes a prefix. */
internal class McpResultFileIo(private val directory: String) {
    private var sequence = 0L

    fun read(name: String): String? {
        val fd = open(path(name), O_RDONLY or O_NOFOLLOW)
        if (fd < 0 && errno == ENOENT) return null
        check(fd >= 0) { "cannot open raw MCP receipt" }
        return try {
            val size = checkedSize(fd)
            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < size) {
                    val count = read(fd, pinned.addressOf(offset), (size - offset).convert())
                    if (count < 0 && errno == EINTR) continue
                    check(count > 0) { "incomplete raw MCP receipt" }
                    offset += count.toInt()
                }
            }
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            close(fd)
        }
    }

    fun write(name: String, text: String) {
        val bytes = text.encodeToByteArray()
        check(bytes.size.toLong() <= MCP_RESULT_ENTRY_BYTES) { "raw MCP receipt exceeds the whole-entry bound" }
        val temporary = "$directory/.mcp-result-${getpid()}-${sequence++}.tmp"
        val fd = open(temporary, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, MCP_RESULT_OWNER_RW)
        check(fd >= 0) { "cannot create raw MCP receipt" }
        try {
            writeAll(fd, bytes)
            check(fsync(fd) == 0) { "cannot flush raw MCP receipt" }
        } finally {
            close(fd)
        }
        try {
            check(rename(temporary, path(name)) == 0) { "cannot publish raw MCP receipt" }
            syncDirectory()
        } finally {
            unlink(temporary)
        }
    }

    fun entry(name: String): McpResultEntry? = memScoped {
        val held = alloc<stat>()
        check(lstat(path(name), held.ptr) == 0) { "cannot inspect raw MCP receipt" }
        if (held.st_mode and MCP_RESULT_KIND_MASK != MCP_RESULT_REGULAR) return@memScoped null
        McpResultEntry(
            name,
            held.st_mtim.tv_sec * MILLIS_PER_SECOND + held.st_mtim.tv_nsec / NANOS_PER_MILLI,
            held.st_size,
        )
    }

    fun remove(name: String) {
        check(unlink(path(name)) == 0 || errno == ENOENT) { "cannot retire raw MCP receipt" }
    }

    private fun path(name: String): String = "$directory/$name"

    private fun checkedSize(fd: Int): Int = memScoped {
        val held = alloc<stat>()
        check(fstat(fd, held.ptr) == 0) { "cannot inspect raw MCP receipt" }
        check(held.st_uid == geteuid() && held.st_mode and MCP_RESULT_KIND_MASK == MCP_RESULT_REGULAR) {
            "raw MCP receipt is not an owned regular file"
        }
        check(held.st_mode and MCP_RESULT_FOREIGN_BITS == 0u) { "raw MCP receipt is not private" }
        check(held.st_size in 0..MCP_RESULT_ENTRY_BYTES) { "raw MCP receipt exceeds the whole-entry bound" }
        held.st_size.toInt()
    }

    private fun writeAll(fd: Int, bytes: ByteArray) {
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                if (count < 0 && errno == EINTR) continue
                check(count > 0) { "cannot write complete raw MCP receipt" }
                offset += count.toInt()
            }
        }
    }

    private fun syncDirectory() {
        val fd = open(directory, O_RDONLY or O_DIRECTORY or O_NOFOLLOW)
        check(fd >= 0) { "cannot open raw MCP receipt directory" }
        try {
            check(fsync(fd) == 0) { "cannot flush raw MCP receipt directory" }
        } finally {
            close(fd)
        }
    }
}

internal data class McpResultEntry(val name: String, val atMs: Long, val bytes: Long)

internal const val MCP_RESULT_ENTRY_BYTES = 128L * 1024 * 1024 + 65_536
internal const val MCP_RESULT_OWNER_RW = 0x180 // 0600
internal const val MCP_RESULT_KIND_MASK = 0xF000u
internal const val MCP_RESULT_REGULAR = 0x8000u
internal const val MCP_RESULT_FOREIGN_BITS = 0x3Fu
private const val MILLIS_PER_SECOND = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L
