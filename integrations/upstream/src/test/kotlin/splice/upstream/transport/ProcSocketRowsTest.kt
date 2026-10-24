// The kernel socket-table parser: native and IPv4-mapped/IPv6 addresses normalize to the keys the
// transport watches, queue sizes read correctly, and malformed rows are skipped, not fatal.
package splice.upstream.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress

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

    private fun kernelRow(local: String, remote: String, queue: String): String =
        "0: ${kernelAddress(local)}:0BB8 ${kernelAddress(remote)}:0FA0 01 $queue:0\n"

    private fun kernelAddress(address: String): String {
        val bytes = InetAddress.getByName(address).address
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
        return List(bytes.size / 4) { "%08X".format(buffer.int) }.joinToString("")
    }

    private fun row(local: Int, remote: Int): String =
        "0: 0100007F:${local.toString(16)} 0100007F:${remote.toString(16)} 01 00000010:00000000\n"
}
