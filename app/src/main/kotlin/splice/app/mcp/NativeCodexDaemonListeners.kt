package splice.app.mcp

import java.nio.file.Files
import java.nio.file.Path

// why: procfs Unix-socket flags are hexadecimal text.
private const val HEX_RADIX = 16

// why: /proc/<pid>/net/unix begins with a column header.
private const val UNIX_SOCKET_HEADER_ROWS = 1

// why: these fields extend through the Unix socket path.
private const val UNIX_SOCKET_MIN_FIELDS = 8

// why: procfs fields distinguish an accepting SOCK_STREAM listener from a bound endpoint.
private const val UNIX_SOCKET_FLAGS_FIELD = 3
private const val UNIX_SOCKET_ACCEPTING_FLAG = 0x10000L
private const val UNIX_SOCKET_TYPE_FIELD = 4
private const val UNIX_SOCKET_STREAM_TYPE = "0001"
private const val UNIX_SOCKET_STATE_FIELD = 5
private const val UNIX_SOCKET_LISTENING_STATE = "01"

// why: this procfs inode is the listener's kernel identity, not the filesystem socket's st_ino.
private const val UNIX_SOCKET_INODE_FIELD = 6
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
            fields[UNIX_SOCKET_FLAGS_FIELD].toLongOrNull(HEX_RADIX)
                ?.and(UNIX_SOCKET_ACCEPTING_FLAG) == UNIX_SOCKET_ACCEPTING_FLAG &&
            fields[UNIX_SOCKET_INODE_FIELD] in inodes

    private fun pathField(fields: List<String>): Path? =
        Path.of(fields[UNIX_SOCKET_PATH_FIELD]).takeIf(Path::isAbsolute)
}
