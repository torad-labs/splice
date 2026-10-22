package splice.app.mcp

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

// why: procfs Unix-socket flags are encoded as hexadecimal text.
private const val HEX_RADIX = 16

// why: /proc/<pid>/net/unix has one column header before its socket rows.
private const val UNIX_SOCKET_HEADER_ROWS = 1

// why: these are the required fields through the Unix socket path in procfs' unix table.
private const val UNIX_SOCKET_MIN_FIELDS = 8

// why: procfs column 4 carries SO_ACCEPTCON, which distinguishes a listener from a bound stream endpoint.
private const val UNIX_SOCKET_FLAGS_FIELD = 3
private const val UNIX_SOCKET_ACCEPTING_FLAG = 0x10000L

// why: procfs column 5 names SOCK_STREAM, the Codex control transport.
private const val UNIX_SOCKET_TYPE_FIELD = 4
private const val UNIX_SOCKET_STREAM_TYPE = "0001"

// why: procfs column 6 marks a listening Unix socket with state 01.
private const val UNIX_SOCKET_STATE_FIELD = 5
private const val UNIX_SOCKET_LISTENING_STATE = "01"

// why: procfs column 7 is the inode matched against this daemon's open descriptors.
private const val UNIX_SOCKET_INODE_FIELD = 6

// why: procfs column 8 begins the listening socket's filesystem path.
private const val UNIX_SOCKET_PATH_FIELD = 7

// why: unix:mode stores file type in these bits; 0xC000 is S_IFSOCK.
private const val UNIX_MODE_TYPE_MASK = 0xF000
private const val UNIX_MODE_SOCKET_TYPE = 0xC000

/** Resolves exactly one live listening Unix socket owned by the daemon named in this CODEX_HOME. */
internal class NativeCodexDaemonSocketDiscovery {
    private val procRoot = Path.of("/proc")
    private val identityReader = NativeCodexDaemonIdentityReader()
    private val socketDescriptor = Regex("""socket:\[(\d+)]""")

    fun find(home: Path): Path? = try {
        findOwnedSocket(home)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: InvalidPathException) {
        null
    } catch (_: DirectoryIteratorException) {
        null
    }

    private fun findOwnedSocket(home: Path): Path? {
        val identity = identityReader.read(home) ?: return null
        val inodes = socketInodes(identity.pid)
        val candidates = listenerPaths(identity.pid, inodes)
        val socket = candidates.singleOrNull() ?: return null
        return socket.takeIf { isSocket(it) && identityReader.isCurrent(identity) }
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

    private fun listenerPaths(pid: Long, inodes: Set<String>): List<Path> =
        Files.readAllLines(procRoot.resolve(pid.toString()).resolve("net/unix"))
            .drop(UNIX_SOCKET_HEADER_ROWS)
            .mapNotNull { row -> listenerPath(row, inodes) }
            .distinct()

    private fun listenerPath(row: String, inodes: Set<String>): Path? {
        val fields = row.trim().split(Regex("\\s+"), limit = UNIX_SOCKET_MIN_FIELDS)
        return if (isOwnedListener(fields, inodes)) pathField(fields) else null
    }

    private fun isOwnedListener(fields: List<String>, inodes: Set<String>): Boolean =
        hasRequiredFields(fields) && ownsListeningSocket(fields, inodes)

    private fun ownsListeningSocket(fields: List<String>, inodes: Set<String>): Boolean =
        isListeningStream(fields) && fields[UNIX_SOCKET_INODE_FIELD] in inodes

    private fun hasRequiredFields(fields: List<String>): Boolean = fields.size >= UNIX_SOCKET_MIN_FIELDS

    private fun isListeningStream(fields: List<String>): Boolean = when {
        fields[UNIX_SOCKET_TYPE_FIELD] != UNIX_SOCKET_STREAM_TYPE -> false
        fields[UNIX_SOCKET_STATE_FIELD] != UNIX_SOCKET_LISTENING_STATE -> false
        else -> fields[UNIX_SOCKET_FLAGS_FIELD].toLongOrNull(HEX_RADIX)
            ?.and(UNIX_SOCKET_ACCEPTING_FLAG) == UNIX_SOCKET_ACCEPTING_FLAG
    }

    private fun pathField(fields: List<String>): Path? =
        Path.of(fields[UNIX_SOCKET_PATH_FIELD]).takeIf(Path::isAbsolute)

    private fun isSocket(path: Path): Boolean = try {
        val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as? Int
        mode != null && mode and UNIX_MODE_TYPE_MASK == UNIX_MODE_SOCKET_TYPE
    } catch (_: java.io.IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }
}
