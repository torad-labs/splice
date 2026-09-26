// NEW: V4-289 (2026-09-26) — what the kernel still holds of an upstream request: the bytes each socket
// wrote, and how many of them the upstream has not acknowledged. RequestWriteBound's watch reads both to
// tell a request the upstream stopped taking from one it is still taking, however slowly, and from one
// it has whole and is answering late. V4-272 bounded the write CALL instead, and a write returns once
// its bytes are in the kernel: a request that fit in the socket buffers (200 KiB and 1 MiB, measured)
// was never cut, and the header wait ran on the turn cap. Its own file: the socket factory and the watch
// both use it, and the /proc parse is a concern of its own.
package splice.upstream.transport

import splice.core.util.Cancellables
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An upstream socket that counts the bytes the kernel accepted from its writes, and says whether a write
 * is waiting in the kernel now. The count is taken below TLS: a TLS socket is layered over this one and
 * writes its records through [getOutputStream] (JDK 21 BaseSSLSocketImpl: `self.getOutputStream()`), and
 * okio writes a plain socket at most a segment at a time, so the count moves every 8 KiB (plain) or one
 * TLS record (16 KiB) at the most.
 */
internal class CountedSocket(private val ledger: SocketLedger) : Socket() {
    private val written = AtomicLong()
    private val writing = AtomicInteger()
    private var stream: OutputStream? = null

    /** This socket's addresses once it connected; its key in [SocketLedger] and in the kernel's table. */
    @Volatile
    var key: SocketKey? = null
        private set

    /** Bytes the kernel accepted from this socket's writes since it connected. */
    val bytesWritten: Long get() = written.get()

    /** Whether a write is waiting in the kernel for room in the send buffer right now. */
    val isWriting: Boolean get() = writing.get() > 0

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        super.connect(endpoint, timeout)
        key = SocketKeys.of(this)?.also { ledger.add(it, this) }
    }

    override fun close() {
        key?.let(ledger::remove)
        super.close()
    }

    @Synchronized
    override fun getOutputStream(): OutputStream = stream ?: Counting(super.getOutputStream()).also { stream = it }

    private inner class Counting(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: Int) = counted(1) { out.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = counted(len) { out.write(b, off, len) }

        private inline fun counted(bytes: Int, write: () -> Unit) {
            writing.incrementAndGet()
            try {
                write()
                written.addAndGet(bytes.toLong())
            } finally {
                writing.decrementAndGet()
            }
        }
    }
}

/** A client's connected [CountedSocket]s by their addresses, so the socket a TLS layer sits over can be
 *  found from the TLS socket OkHttp hands an interceptor, which reports the same addresses. */
internal class SocketLedger {
    private val sockets = ConcurrentHashMap<SocketKey, CountedSocket>()

    fun add(key: SocketKey, socket: CountedSocket) {
        sockets[key] = socket
    }

    fun remove(key: SocketKey) {
        sockets.remove(key)
    }

    /** The counted socket under [socket]: itself when plain, the one it is layered over when TLS. */
    fun find(socket: Socket): CountedSocket? =
        socket as? CountedSocket ?: SocketKeys.of(socket)?.let(sockets::get)
}

/** A connection's two ends, each `<address hex>:<port>`, an IPv4-mapped IPv6 address written as IPv4. */
internal data class SocketKey(val local: String, val remote: String)

/** The one spelling of a [SocketKey], for a Java socket and for a row of the kernel's table alike. */
internal object SocketKeys {
    fun of(socket: Socket): SocketKey? {
        val remote = socket.inetAddress ?: return null
        return SocketKey(end(socket.localAddress.address, socket.localPort), end(remote.address, socket.port))
    }

    fun end(address: ByteArray, port: Int): String {
        val mapped = address.size == IPV6_BYTES &&
            address.copyOfRange(0, IPV4_MAPPED_PREFIX.size).contentEquals(IPV4_MAPPED_PREFIX)
        val bytes = if (mapped) address.copyOfRange(IPV6_BYTES - IPV4_BYTES, IPV6_BYTES) else address
        return "${HexFormat.of().formatHex(bytes)}:$port"
    }
}

/** The kernel's send queues, read once per watch tick; null where this system keeps no table to read. */
internal fun interface SendQueues {
    fun read(): SendQueueTable?
}

/** One read of the send queues: the bytes each connection holds that its peer has not acknowledged. */
internal class SendQueueTable(private val unacked: Map<SocketKey, Long>) {
    /** [socket]'s unacknowledged bytes, or null when the table does not list it. */
    fun unacked(socket: CountedSocket): Long? = socket.key?.let(unacked::get)
}

/**
 * Linux's table, /proc/net/tcp and /proc/net/tcp6: `tx_queue` there is `write_seq - snd_una`, the bytes
 * written that the peer has not acknowledged. Anywhere else (macOS) neither file exists and [read] says
 * null: the watch then bounds the write call alone, as V4-272 did, and never guesses at the kernel.
 */
internal object ProcNetTcp : SendQueues {
    private val tables = listOf(Path.of("/proc/net/tcp"), Path.of("/proc/net/tcp6"))
    private val whitespace = Regex("\\s+")

    override fun read(): SendQueueTable? {
        val readable = tables.filter(Files::isReadable)
        if (readable.isEmpty()) return null
        val unacked = HashMap<SocketKey, Long>()
        readable.forEach { table ->
            val lines = Cancellables.runCatchingCancellable { Files.readAllLines(table) }
            lines.getOrElse {
                Cancellables.discard(lines, "a table that cannot be read this tick lists nothing; the next reads it")
                emptyList()
            }.drop(1).forEach { line -> row(line)?.let { (key, queued) -> unacked[key] = queued } }
        }
        return SendQueueTable(unacked)
    }

    /** `sl local_address rem_address st tx_queue:rx_queue ...`, addresses in hex words of host byte order. */
    private fun row(line: String): Pair<SocketKey, Long>? {
        val fields = line.trim().split(whitespace)
        val ends = listOf(LOCAL_FIELD, REMOTE_FIELD).mapNotNull { fields.getOrNull(it)?.let(::end) }
        val queued = fields.getOrNull(QUEUES_FIELD)?.substringBefore(':')?.toLongOrNull(HEX)
        return if (ends.size == 2 && queued != null) SocketKey(ends[0], ends[1]) to queued else null
    }

    /** `<address hex>:<port hex>`, the address as 32-bit words the kernel printed in host byte order. */
    private fun end(field: String): String? {
        val hex = field.substringBefore(':')
        val port = field.substringAfter(':', "").toIntOrNull(HEX)
        val words = hex.chunked(WORD_HEX).mapNotNull { it.toLongOrNull(HEX) }
        val whole = hex.isNotEmpty() && words.size * WORD_HEX == hex.length
        if (port == null || !whole) return null
        val bytes = ByteBuffer.allocate(words.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        words.forEach { bytes.putInt(it.toInt()) }
        return SocketKeys.end(bytes.array(), port)
    }
}

// why: the columns of /proc/net/tcp{,6} (Documentation/networking/proc_net_tcp.rst), counted from 0.
private const val LOCAL_FIELD = 1

// why: the remote address column of the same table, counted from 0.
private const val REMOTE_FIELD = 2

// why: the `tx_queue:rx_queue` column of the same table, counted from 0.
private const val QUEUES_FIELD = 4

// why: the table prints each 32-bit address word as 8 hex digits.
private const val WORD_HEX = 8

// why: every number in the table is hexadecimal.
private const val HEX = 16

// why: an IPv6 address is 16 bytes (RFC 8200), the size an IPv4-mapped one is recognised by.
private const val IPV6_BYTES = 16

// why: an IPv4 address is 4 bytes, the tail of an IPv4-mapped IPv6 address (RFC 4291 §2.5.5.2).
private const val IPV4_BYTES = 4

// why: ::ffff:a.b.c.d is ten zero bytes and two 0xff before the IPv4 address (RFC 4291 §2.5.5.2).
private val IPV4_MAPPED_PREFIX = ByteArray(IPV6_BYTES - IPV4_BYTES).also {
    it[it.size - 2] = 0xff.toByte()
    it[it.size - 1] = 0xff.toByte()
}
