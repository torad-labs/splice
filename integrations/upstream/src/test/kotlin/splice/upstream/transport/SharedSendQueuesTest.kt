// NEW: concurrent watches share one kernel-table read and materialize only tracked socket rows.
package splice.upstream.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class SharedSendQueuesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `three clients watched in one tick read the socket table once`() {
        withWatches { watch, reads, queues ->
            watch.tick()
            assertEquals(1, reads.get(), "one input read for all clients in this tick")
            assertEquals(3, queues.sumOf { it.allocatedRows }, "only the watched sockets become rows")
            watch.tick()
            assertEquals(2, reads.get(), "the next tick reads fresh data, never a time-based cache")
        }
    }

    @Test
    fun `untracked sockets allocate no row key endpoint string or pair`() {
        withWatches { watch, _, queues ->
            watch.tick()
            assertEquals(3, queues.sumOf { it.allocatedRows }, "five unrelated sockets allocate no rows")
        }
    }

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
        assertEquals(3, parser.allocatedRows)
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
        assertEquals(1, parser.allocatedRows)
    }

    private fun kernelRow(local: String, remote: String, queue: String): String =
        "0: ${kernelAddress(local)}:0BB8 ${kernelAddress(remote)}:0FA0 01 $queue:0\n"

    private fun kernelAddress(address: String): String {
        val bytes = InetAddress.getByName(address).address
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
        return List(bytes.size / 4) { "%08X".format(buffer.int) }.joinToString("")
    }

    private fun withWatches(check: (StallWatch, AtomicInteger, List<ProcNetTcp>) -> Unit) {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 8, loopback).use { server ->
            val sockets = List(3) { CountedSocket(SocketLedger()) }
            val accepted = mutableListOf<java.net.Socket>()
            val watched = mutableListOf<WatchedWrite>()
            val watch = StallWatch(3_600_000)
            val reads = AtomicInteger()
            val table = dir.resolve("tcp")
            val queues = List(3) {
                ProcNetTcp(
                    LogSink { },
                    listOf(table),
                    ProcTableRead {
                        reads.incrementAndGet()
                        Files.newInputStream(it)
                    },
                )
            }
            try {
                sockets.forEach { socket ->
                    socket.connect(InetSocketAddress(loopback, server.localPort), 1_000)
                    accepted += server.accept()
                }
                val unrelated = List(5) { row(3_000 + it, 4_000 + it) }
                val content = "header\n" + unrelated.joinToString("") +
                    sockets.joinToString("") { row(it.localPort, it.port) }
                Files.writeString(table, content)
                val client = OkHttpClient()
                sockets.forEachIndexed { index, socket ->
                    val call = client.newCall(Request.Builder().url("http://127.0.0.1/").build())
                    watched += watch.start(WatchedWrite(socket, 60_000, call, queues[index]))
                }
                check(watch, reads, queues)
            } finally {
                watched.forEach(watch::stop)
                sockets.forEach { it.close() }
                accepted.forEach { it.close() }
            }
        }
    }

    private fun row(local: Int, remote: Int): String =
        "0: 0100007F:${local.toString(16)} 0100007F:${remote.toString(16)} 01 00000010:00000000\n"
}
