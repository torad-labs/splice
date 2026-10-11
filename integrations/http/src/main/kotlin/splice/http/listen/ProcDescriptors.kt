// NEW: reads what an inherited descriptor is from /proc, so the daemon can check a socket manager's hand-off before it
// serves on anything. /proc/self/fd/N names the socket's inode; /proc/net/tcp has one row per IPv4 TCP socket with its
// local address, port and state, keyed by that same inode.
package splice.http.listen

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What one inherited descriptor IS: the loopback endpoint it holds, and whether it is listening on it. */
internal data class DescriptorFacts(val address: String, val port: Int, val listening: Boolean)

/** Answers for one descriptor, or null when it is not an IPv4 TCP socket this process holds. */
internal fun interface DescriptorInspector {
    fun inspect(fd: Int): DescriptorFacts?
}

// /proc/net/tcp prints state 0A for a socket in LISTEN.
private const val LISTEN_STATE = "0A"

// The columns of a /proc/net/tcp row, in the kernel's format.
// why: the local "address:port" endpoint is the second column of a row.
private const val LOCAL_COLUMN = 1

// why: the socket's state is the fourth column.
private const val STATE_COLUMN = 3

// why: the inode a descriptor's /proc link names is the tenth column.
private const val INODE_COLUMN = 9

// why: /proc prints every address and port in hexadecimal.
private const val HEX = 16

// why: an IPv4 address prints as 8 hex digits.
private const val ADDRESS_DIGITS = 8

// why: two hex digits per byte.
private const val DIGITS_PER_BYTE = 2

// why: the address is printed in host byte order, so byte 3 of 0..3 carries the first octet.
private const val HIGHEST_BYTE = 3

// A /proc/net/tcp endpoint is exactly "address:port".
private const val ENDPOINT_PARTS = 2

private val COLUMNS = Regex("\\s+")
private val SOCKET_INODE = Regex("socket:\\[(\\d+)]")

internal class ProcDescriptors(private val proc: Path = Path.of("/proc")) : DescriptorInspector {
    override fun inspect(fd: Int): DescriptorFacts? {
        val row = inodeOf(fd)?.let(::rowFor) ?: return null
        return factsOf(row)
    }

    /** One row's endpoint and state, or null when the row is not the "address:port state" shape this reads. */
    private fun factsOf(row: List<String>): DescriptorFacts? {
        val endpoint = row[LOCAL_COLUMN].split(':')
        if (endpoint.size != ENDPOINT_PARTS) return null
        val address = dotted(endpoint[0])
        val port = endpoint[1].toIntOrNull(HEX)
        val listening = row[STATE_COLUMN] == LISTEN_STATE
        return if (address == null) null else port?.let { DescriptorFacts(address, it, listening) }
    }

    /** The row /proc/net/tcp holds for [inode], or null when this process's socket table has none. */
    private fun rowFor(inode: String): List<String>? =
        Files.readAllLines(proc.resolve("net/tcp"))
            .asSequence()
            .drop(1)
            .map { it.trim().split(COLUMNS) }
            .firstOrNull { it.size > INODE_COLUMN && it[INODE_COLUMN] == inode }

    /** The socket inode descriptor [fd] points at, or null when it points at something that is not a socket. */
    private fun inodeOf(fd: Int): String? {
        val target = try {
            Files.readSymbolicLink(proc.resolve("self/fd/$fd")).toString()
        } catch (_: IOException) {
            // The descriptor is not open, or is not a symlink this process may read: either way it is not a socket
            // this daemon can serve on, and the caller refuses the hand-off naming it.
            return null
        }
        return SOCKET_INODE.matchEntire(target)?.groupValues?.get(1)
    }

    /** /proc prints an IPv4 address as eight hex digits in host byte order, so 127.0.0.1 reads 0100007F. */
    private fun dotted(hex: String): String? =
        if (hex.length != ADDRESS_DIGITS) {
            null
        } else {
            (HIGHEST_BYTE downTo 0).joinToString(".") { byte ->
                hex.substring(byte * DIGITS_PER_BYTE, byte * DIGITS_PER_BYTE + DIGITS_PER_BYTE).toInt(HEX).toString()
            }
        }
}
