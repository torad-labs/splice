// NEW: V4-292 — a send-queue table that cannot be read says so. ProcNetTcp read a present but unreadable
// table as an empty one, so every watched socket fell back to the write call alone while the boot line
// said a request the upstream stops acknowledging is cut.
package splice.upstream.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class SendQueuesTest {

    @TempDir
    lateinit var dir: Path

    private val logs = CopyOnWriteArrayList<String>()
    private val log = LogSink { logs += it }

    @Test
    fun `two present tables whose reads fail read as no table, not an empty one - V4-292`() {
        val queues = ProcNetTcp(log, listOf(unreadable("tcp"), unreadable("tcp6")))

        assertNull(queues.read(), "no table read, so there is no table")
    }

    @Test
    fun `with the tables present but unreadable the boot line names the degraded mode - V4-292`() {
        val sockets = UpstreamSockets(queues = ProcNetTcp(log, listOf(unreadable("tcp"), unreadable("tcp6"))))

        UpstreamTransport().client(1_000, log, AtomicBoolean(false), 1_000, sockets).close()

        val boot = logs.single { it.startsWith("[upstream] tcp_nodelay") }
        assertTrue("cannot be read" in boot && "waits for the turn cap" in boot, boot)
    }

    @Test
    fun `a table that stops reading is logged once with its cause, and once when it reads again - V4-292`() {
        val table = dir.resolve("tcp")
        Files.writeString(table, HEADER + ROW)
        val queues = ProcNetTcp(log, listOf(table))
        assertNotNull(queues.read(), "the table reads")
        assertEquals(emptyList<String>(), logs, "a table that reads says nothing")

        Files.delete(table)
        Files.createDirectory(table)
        repeat(3) { assertNull(queues.read(), "the only table stopped reading") }
        val stopped = logs.single()
        assertTrue("$table could not be read (" in stopped && "until it reads again" in stopped, stopped)

        Files.delete(table)
        Files.writeString(table, HEADER + ROW)
        repeat(2) { assertNotNull(queues.read(), "the table reads again") }
        assertEquals(2, logs.size, "one line for the stop and one for the recovery: $logs")
        assertTrue("$table reads again" in logs.last(), logs.last())
    }

    /** A table path that exists and reports readable, whose read fails: a directory. */
    private fun unreadable(name: String): Path = Files.createDirectory(dir.resolve(name))

    private companion object {
        const val HEADER =
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
        const val ROW =
            "   0: 0100007F:0BB8 0100007F:D431 01 00000010:00000000 00:00000000 00000000  1000        0 1\n"
    }
}
