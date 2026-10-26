// The kernel socket-table parser: native and IPv4-mapped/IPv6 addresses normalize to the keys the
// transport watches, queue sizes read correctly, and malformed rows are skipped, not fatal. However a
// table is cut into reads, down to one byte each, the same rows come out.
package splice.upstream.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HexFormat
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

class ProcSocketRowsTest {
    @Test
    fun `IPv4 mapped IPv6 and native IPv6 retain socket normalization and queue values`() {
        val ipv4 = SocketKey("7f000001:3000", "7f000001:4000")
        val ipv6 = SocketKey("20010db8000000000000000000000001:3000", "20010db8000000000000000000000002:4000")
        val parser = ProcSocketRows(setOf(ipv4, ipv6))
        val text = "header\n" +
            kernelRow("127.0.0.1", "127.0.0.1", "10") +
            "0: 0000000000000000FFFF00000100007F:0BB8 " +
            "0000000000000000FFFF00000100007F:0FA0 01 00000020:0\n" +
            kernelRow("2001:db8::1", "2001:db8::2", "100000000").trimEnd()
        val rows = parser.read(text.byteInputStream())
        assertEquals(32L, rows[ipv4])
        assertEquals(4_294_967_296L, rows[ipv6])
    }

    @Test
    fun `malformed and untracked rows are skipped while long suffixes stay bounded`() {
        val key = SocketKey("7f000001:3000", "7f000001:4000")
        val parser = ProcSocketRows(setOf(key))
        val text = "header\r\n" +
            "bad\n" +
            "0: 0100007F0:0BB8 0100007F:0FA0 01 10:0\n" +
            "0: 0100007F:ZZZZ 0100007F:0FA0 01 10:0\n" +
            "0: 0100007F:0BB8 0100007F:0FA0 01 ZZZZ:0\n" +
            "0: 0100007F:0BB8 0100007F:0FA0 01 FFFFFFFFFFFFFFFF:0\n" +
            row(5000, 6000) +
            kernelRow("127.0.0.1", "127.0.0.1", "21").trimEnd() + " " + "x".repeat(2000)
        assertEquals(mapOf(key to 33L), parser.read(text.byteInputStream()))
    }

    // The rows of a table are the rows its lines write, wherever the stream cuts it into reads: a row that
    // straddles two reads, a last row with no newline, a long row whose bytes past the prefix span reads,
    // and a header or half-read row left over from the table before it. The expected rows come from the
    // lines the generator wrote, never from the parser.
    @Test
    fun `any split of a table into reads gives its modelled rows, with one parser reading two tables in turn`() {
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val kernel = Kernel(random)
            val parser = ProcSocketRows(kernel.keys)
            listOf("first", "second").forEach { name ->
                val table = kernel.table()
                val reads = SplitReads(table.text, random, oneByte = random.nextInt(ONE_BYTE_ODDS) == 0)
                assertEquals(table.rows, parser.read(reads)) { "seed $seed, $name table, ${reads.describe()}" }
            }
        }
    }

    // A stream that answers zero bytes has nothing more to give: the table ends there, with the rows it
    // gave before, as it did when the parser read through a buffered stream.
    @Test
    fun `a stream that answers zero bytes ends the table there, with the rows read before it`() {
        val first = SocketKey("7f000001:3000", "7f000001:4000")
        val second = SocketKey("7f000001:3001", "7f000001:4000")
        val text = "header\n" +
            "0: 0100007F:0BB8 0100007F:0FA0 01 00000010:0\n" +
            "0: 0100007F:0BB9 0100007F:0FA0 01 00000020:0"
        val reads = SplitReads(text, Random(0), oneByte = false, end = 0)

        assertEquals(mapOf(first to 16L, second to 32L), ProcSocketRows(setOf(first, second)).read(reads))
    }

    private fun kernelRow(local: String, remote: String, queue: String): String =
        "0: ${kernelAddress(local)}:0BB8 ${kernelAddress(remote)}:0FA0 01 $queue:0\n"

    private fun kernelAddress(address: String): String {
        val bytes = InetAddress.getByName(address).address
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
        return List(bytes.size / 4) { "%08X".format(buffer.int) }.joinToString("")
    }

    private fun row(local: Int, remote: Int): String =
        "0: 0100007F:${local.toString(16)} 0100007F:${remote.toString(16)} 01 00000010:00000000\n"

    /** One end of a connection: its address as lowercase hex, IPv4 as 8 digits or IPv6 as 32, and its port. */
    private data class End(val hex: String, val port: Int)

    /** A tracked connection, both ends of one family, spelled as [SocketKey] documents it. */
    private data class Connection(val local: End, val remote: End) {
        val key = SocketKey("${local.hex}:${local.port}", "${remote.hex}:${remote.port}")
        val native6: Boolean get() = local.hex.length == 2 * IPV6_BYTES
    }

    /** A line of a table, and the one row a reader must take from it, if any. */
    private data class Line(val text: String, val counts: Pair<SocketKey, Long>? = null)

    /** A whole table's text and the rows its lines write. */
    private data class Table(val text: String, val rows: Map<SocketKey, Long>)

    /** One seed's tracked connections, and the tables the kernel might list them in. */
    private class Kernel(private val random: Random) {
        private val rows = Rows(random)
        private val connections = List(random.nextInt(1, MAX_KEYS + 1)) {
            connection(LOCAL_PORT + it, REMOTE_PORT + it)
        }
        val keys: Set<SocketKey> = connections.map(Connection::key).toSet()

        /** A header, then 0 to [MAX_LINES] lines, each tracked key in at most one of them. */
        fun table(): Table {
            val unused = connections.shuffled(random).toMutableList()
            val body = List(random.nextInt(MAX_LINES + 1)) { line(unused) }
            val header = unused.firstOrNull()?.takeIf { random.nextBoolean() }?.let { rows.counted(it).text } ?: HEADER
            val lines = listOf(header) + body.map(Line::text)
            val text = lines.withIndex().joinToString("") { (at, each) ->
                if (at == lines.lastIndex && random.nextInt(UNTERMINATED_ODDS) == 0) each else each + eol()
            }
            return Table(text, body.mapNotNull(Line::counts).toMap())
        }

        private fun line(unused: MutableList<Connection>): Line {
            val kind = random.nextInt(LINE_KINDS)
            val own = unused.takeIf { kind < OWN_KINDS }?.removeLastOrNull()
            return when (kind) {
                0, 1, 2 -> own?.let(rows::counted) ?: stranger()
                3 -> own?.let(rows::nonHex) ?: stranger()
                4 -> rows.reversed(connections.random(random))
                5 -> rows.short(connections.random(random))
                else -> stranger()
            }
        }

        /** A socket on ports no tracked key uses. */
        private fun stranger(): Line =
            rows.untracked(connection(STRANGER_PORT + random.nextInt(PORTS), STRANGER_PORT + random.nextInt(PORTS)))

        private fun connection(localPort: Int, remotePort: Int): Connection {
            val native6 = random.nextBoolean()
            return Connection(end(native6, localPort), end(native6, remotePort))
        }

        private fun end(native6: Boolean, port: Int): End {
            val bytes = random.nextBytes(if (native6) IPV6_BYTES else IPV4_BYTES)
            if (native6) bytes[0] = NOT_MAPPED
            return End(HEX.formatHex(bytes), port)
        }

        private fun eol(): String = if (random.nextInt(CRLF_ODDS) == 0) "\r\n" else "\n"
    }

    /** How the kernel writes a connection's row, and whether a reader must count it. */
    private class Rows(private val random: Random) {
        fun counted(connection: Connection): Line {
            val queue = random.nextLong(QUEUE_LIMIT)
            return Line(row(connection, queued(queue)), connection.key to queue)
        }

        fun nonHex(connection: Connection): Line = Line(row(connection, NON_HEX_QUEUES.random(random)))

        /** The other direction of a tracked connection. */
        fun reversed(connection: Connection): Line =
            Line(row(connection, queued(random.nextLong(QUEUE_LIMIT)), reversed = true))

        fun untracked(connection: Connection): Line = Line(row(connection, queued(random.nextLong(QUEUE_LIMIT))))

        /** A blank line, or a row of fewer than five fields. */
        fun short(connection: Connection): Line = Line((SHORT_ROWS + row(connection, null)).random(random))

        private fun queued(queue: Long): String = "${upper(queue, 8)}:${upper(random.nextLong(QUEUE_LIMIT), 8)}"

        /** [connection]'s row with [queue] as its fifth field, or only its first four when [queue] is null. */
        private fun row(connection: Connection, queue: String?, reversed: Boolean = false): String {
            val near = if (reversed) connection.remote else connection.local
            val far = if (reversed) connection.local else connection.remote
            val wide = connection.native6 || random.nextBoolean()
            val ordinal = random.nextInt(ORDINALS).toString().padStart(4)
            val fields = "$ordinal: ${written(near, wide)} ${written(far, wide)} 01"
            return if (queue == null) fields else "$fields $queue${tail()}"
        }

        /** An address the way the kernel writes it: each 4-byte word as the native-order int in hex digits, and
         *  an IPv4 address of a tcp6 row as the IPv4-mapped IPv6 address it is. */
        private fun written(end: End, wide: Boolean): String {
            val bytes = HEX.parseHex(end.hex)
            val address = if (wide && bytes.size == IPV4_BYTES) MAPPED_PREFIX + bytes else bytes
            val words = ByteBuffer.wrap(address).order(ByteOrder.nativeOrder())
            val digits = List(address.size / Int.SIZE_BYTES) { upper(words.int.toLong() and WORD_MASK, 8) }
            return digits.joinToString("") + ":" + upper(end.port.toLong(), 4)
        }

        /** Most rows end in the kernel's other columns, some in a long one, some in nothing. */
        private fun tail(): String {
            val draw = random.nextInt(TAIL_DRAWS)
            return when {
                draw < NO_TAIL -> ""
                draw < NO_TAIL + LONG_TAILS -> " " + "x".repeat(random.nextInt(LONG_TAIL_MIN, LONG_TAIL_MAX + 1))
                else -> " 00:00000000 00000000  1000  0 ${random.nextInt(INODES)} 1 0000000000000000 20 4 30 10 -1"
            }
        }

        private fun upper(value: Long, digits: Int): String =
            value.toString(HEX_RADIX).uppercase().padStart(digits, '0')
    }

    /**
     * [text] handed back in reads of planned sizes: one byte each, or from 1 to [MOST_PLANNED] bytes, never
     * more than the read asks for and never empty before the end. After its last byte it answers [end], which
     * is -1 unless a test wants it to answer zero.
     */
    private class SplitReads(
        text: String,
        private val random: Random,
        private val oneByte: Boolean,
        private val end: Int = -1,
    ) : InputStream() {
        private val bytes = text.toByteArray()
        private val sizes = ArrayList<Int>()
        private var at = 0
        private var afterEnd = 0

        override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and BYTE_MASK else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= bytes.size) return answerAtEnd()
            val count = minOf(planned(), len, bytes.size - at)
            System.arraycopy(bytes, at, b, off, count)
            at += count
            sizes += count
            return count
        }

        /** What was served, for a failure message: only the first sizes, as a table read by the byte has thousands. */
        fun describe(): String =
            if (oneByte) "one byte per read" else "${sizes.size} reads, the first sizes ${sizes.take(SHOWN_SIZES)}"

        // A reader that keeps asking a stream with nothing left fails the test, where it would hang it.
        private fun answerAtEnd(): Int {
            afterEnd++
            check(afterEnd < GIVE_UP_AFTER) { "the reader kept asking a stream that has nothing more to give" }
            return end
        }

        /** Log-uniform, so a table meets reads of every scale, from a few bytes to more than a chunk. */
        private fun planned(): Int =
            if (oneByte) 1 else exp(random.nextDouble() * ln(MOST_PLANNED.toDouble())).toInt()
    }

    private companion object {
        val HEX: HexFormat = HexFormat.of()

        /** ::ffff: in front of an IPv4 address, ten zero bytes then two 0xff (RFC 4291 section 2.5.5.2). */
        val MAPPED_PREFIX = ByteArray(10) + byteArrayOf(-1, -1)

        /** The header the kernel writes above its rows. */
        const val HEADER =
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

        /** Queue columns that are not hex numbers. */
        val NON_HEX_QUEUES = listOf("ZZZZ:00000000", "12G4:0", ":00000000", "-1:0", "0x10:0", "GG")

        /** Lines that are no row at all. */
        val SHORT_ROWS = listOf("", "   ", "bad", "0:")

        const val SEEDS = 400
        const val MAX_KEYS = 6
        const val MAX_LINES = 60
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
        const val HEX_RADIX = 16
        const val BYTE_MASK = 0xFF
        const val WORD_MASK = 0xFFFFFFFFL

        /** A first byte that keeps a random IPv6 address from being an IPv4-mapped one. */
        const val NOT_MAPPED: Byte = 0x20

        // Tracked keys use local ports from 3000 and remote ports from 4000; every other socket uses ports from
        // 5000. A reversed row puts a 4000 port first, which no tracked key does, so it never matches one.
        const val LOCAL_PORT = 3000
        const val REMOTE_PORT = 4000
        const val STRANGER_PORT = 5000
        const val PORTS = 1000
        const val ORDINALS = 10_000
        const val INODES = 1_000_000

        /** Queues are uppercase hex below 2^40. */
        const val QUEUE_LIMIT = 1L shl 40

        // A line is one of LINE_KINDS kinds, drawn alike: 0 to 2 a tracked key's row, 3 that row with a queue no hex
        // number, 4 a tracked connection's reverse, 5 a blank or short line, the rest a socket nobody tracks. The
        // first OWN_KINDS need a tracked key this table has not used yet, so a key is in one line at most.
        const val LINE_KINDS = 10
        const val OWN_KINDS = 4

        // A row's tail is one of TAIL_DRAWS: NO_TAIL of them end the row at its queue, LONG_TAILS run from
        // LONG_TAIL_MIN to LONG_TAIL_MAX bytes past it, beyond the parser's prefix, and the rest are the kernel's
        // other columns.
        const val TAIL_DRAWS = 20
        const val NO_TAIL = 3
        const val LONG_TAILS = 5
        const val LONG_TAIL_MIN = 400
        const val LONG_TAIL_MAX = 2000

        // One line in CRLF_ODDS ends in CRLF, one table in UNTERMINATED_ODDS has no newline after its last line,
        // and one table in ONE_BYTE_ODDS is read a byte at a time.
        const val CRLF_ODDS = 5
        const val UNTERMINATED_ODDS = 3
        const val ONE_BYTE_ODDS = 4

        /** Three chunks of 8 KiB, the most a planned read asks for; a read is cut to what the reader asks. */
        const val MOST_PLANNED = 3 * 8192
        const val SHOWN_SIZES = 24
        const val GIVE_UP_AFTER = 1000
    }
}
