package splice.app.mcp

import java.nio.file.Files
import java.nio.file.Path

// why: /proc reports the Unix-socket Flags column in hexadecimal, so parsing uses base 16.
private const val PROCFS_HEX_RADIX = 16

// why: /proc/<pid>/net/unix begins with a column header.
private const val UNIX_SOCKET_HEADER_ROWS = 1

// why: the eight procfs columns end with the socket path.
private const val UNIX_SOCKET_MIN_FIELDS = 8

// why: Flags is the fourth zero-based column in the procfs Unix-socket record.
private const val UNIX_SOCKET_FLAGS_FIELD = 3

// why: procfs sets bit 16 when a Unix stream socket accepts connections.
private const val UNIX_SOCKET_ACCEPTING_FLAG = 0x10000L

// why: Type is the fifth zero-based column in the procfs Unix-socket record.
private const val UNIX_SOCKET_TYPE_FIELD = 4
private const val UNIX_SOCKET_STREAM_TYPE = "0001"

// why: St follows Type as the sixth zero-based column in the procfs record.
private const val UNIX_SOCKET_STATE_FIELD = 5
private const val UNIX_SOCKET_LISTENING_STATE = "01"

// why: the seventh procfs column is the kernel listener inode, not filesystem st_ino.
private const val UNIX_SOCKET_INODE_FIELD = 6

// why: Path is the eighth column; the split limit preserves spaces in its value.
private const val UNIX_SOCKET_PATH_FIELD = 7

/** Reads the one kernel listener a recorded daemon process currently holds. */
internal class NativeCodexDaemonListeners {
    private val procRoot = Path.of("/proc")
    private val socketDescriptor = Regex("""socket:\[(\d+)]""")

    fun forDaemon(pid: Long): List<NativeCodexListener> {
        val inodes = socketInodes(pid)
        return listenerPaths(pid, inodes)
    }

    private fun socketInodes(pid: Long): Set<String> =
        Files.newDirectoryStream(procRoot.resolve(pid.toString()).resolve("fd")).use { descriptors ->
            descriptors.mapNotNull { descriptor -> descriptorInode(descriptor) }.toSet()
        }

    private fun descriptorInode(descriptor: Path): String? = try {
        val target = Files.readSymbolicLink(descriptor).toString()
        socketDescriptor.matchEntire(target)?.groupValues?.getOrNull(1)
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun listenerPaths(pid: Long, inodes: Set<String>): List<NativeCodexListener> =
        Files.readAllLines(procRoot.resolve(pid.toString()).resolve("net/unix"))
            .drop(UNIX_SOCKET_HEADER_ROWS)
            .mapNotNull { row -> listenerPath(row, inodes) }
            .distinct()

    private fun listenerPath(row: String, inodes: Set<String>): NativeCodexListener? {
        val fields = row.trim().split(Regex("\\s+"), limit = UNIX_SOCKET_MIN_FIELDS)
        val inode = fields.getOrNull(UNIX_SOCKET_INODE_FIELD)?.toLongOrNull()
        return if (inode != null && isOwnedListener(fields, inodes)) {
            pathField(fields)?.let { NativeCodexListener(it, inode) }
        } else {
            null
        }
    }

    private fun isOwnedListener(fields: List<String>, inodes: Set<String>): Boolean =
        fields.size >= UNIX_SOCKET_MIN_FIELDS &&
            fields[UNIX_SOCKET_TYPE_FIELD] == UNIX_SOCKET_STREAM_TYPE &&
            fields[UNIX_SOCKET_STATE_FIELD] == UNIX_SOCKET_LISTENING_STATE &&
            fields[UNIX_SOCKET_FLAGS_FIELD].toLongOrNull(PROCFS_HEX_RADIX)
                ?.and(UNIX_SOCKET_ACCEPTING_FLAG) == UNIX_SOCKET_ACCEPTING_FLAG &&
            fields[UNIX_SOCKET_INODE_FIELD] in inodes

    private fun pathField(fields: List<String>): Path? =
        Path.of(fields[UNIX_SOCKET_PATH_FIELD]).takeIf(Path::isAbsolute)
}
