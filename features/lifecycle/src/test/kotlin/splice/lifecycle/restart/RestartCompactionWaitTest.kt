// NEW: V4-216 — `splice restart` stops a daemon only once no compaction is in flight, unless --now.
// Driven through the verb's own stop path (stopIfRunning) against a loopback daemon that serves
// /health, /api/heads with V4-213's live rows, and the shutdown route, which closes the listener the
// way a stopping daemon frees its port.
package splice.lifecycle.restart

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.lifecycle.upgrade.CompactionSlot
import splice.lifecycle.upgrade.CompactionWait
import splice.lifecycle.upgrade.InflightRead
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class RestartCompactionWaitTest {

    /** A daemon whose first [compactReads] /api/heads answers list one in-flight turn that is a
     *  compaction when [compact], [ageMs] old; every answer after lists nothing in flight. */
    private class FakeDaemon(private val compactReads: Int, private val compact: Boolean, private val ageMs: Long) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port
        private val headsReads = AtomicInteger()

        /** Whether the last /api/heads answer before the shutdown still listed the turn. */
        @Volatile var turnListedAtShutdown: Boolean? = null

        init {
            server.createContext("/health") { ex -> ex.reply("""{"version":"test-daemon"}""") }
            server.createContext("/api/heads") { ex ->
                val listed = headsReads.incrementAndGet() <= compactReads
                val live = if (listed) {
                    """[{"label":"b2e4d8f1 gpt-5.6-sol","compact":$compact,"phase":"streaming",""" +
                        """"age_ms":$ageMs,"idle_ms":0}]"""
                } else {
                    "[]"
                }
                val inflight = if (listed) 1 else 0
                ex.reply("""{"heads":[{"key":"claudex","port":$port,"gate":{"inflight":$inflight,"live":$live}}]}""")
            }
            server.createContext("/api/daemon/shutdown") { ex ->
                turnListedAtShutdown = headsReads.get() <= compactReads
                ex.sendResponseHeaders(202, -1)
                ex.close()
                Thread { server.stop(0) }.start()
            }
            server.start()
        }

        private fun HttpExchange.reply(body: String) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    private val lines = CopyOnWriteArrayList<String>()

    private fun restart(tmp: Path): RestartCommand {
        val stateDir = Files.createDirectories(tmp.resolve("state"))
        Files.writeString(stateDir.resolve("mgmt-key"), "test-mgmt-key")
        val env = EnvReader { name -> if (name == "CLAUDEX_STATE_DIR") stateDir.toString() else null }
        return RestartCommand(TerminalOutput { lines += it }, TerminalOutput { lines += it }, env, RunningJar { null })
    }

    /** RED before V4-216: the verb read the head ports and stopped at once, cutting the compaction
     *  whose answer its client's retry was about to need. */
    @Test
    fun `a restart with a compaction in flight does not stop the daemon until the compaction is gone`(
        @TempDir tmp: Path,
    ) {
        val daemon = FakeDaemon(compactReads = 1, compact = true, ageMs = 130_000)
        val stopped = try {
            restart(tmp).stopIfRunning(daemon.port, emptyList())
        } finally {
            daemon.server.stop(0)
        }
        assertTrue(stopped, lines.joinToString("\n"))
        assertEquals(false, daemon.turnListedAtShutdown, "stopped while the compaction was in flight:\n$lines")
        assertTrue(lines.contains("splice: waiting for 1 compaction (claudex, 2m10s in)"), lines.joinToString("\n"))
    }

    @Test
    fun `ordinary turns in flight never hold a restart`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(compactReads = Int.MAX_VALUE, compact = false, ageMs = 130_000)
        val stopped = try {
            restart(tmp).stopIfRunning(daemon.port, emptyList())
        } finally {
            daemon.server.stop(0)
        }
        assertTrue(stopped, lines.joinToString("\n"))
        assertEquals(true, daemon.turnListedAtShutdown, "the stop's own drain is theirs, not this wait")
        assertFalse(lines.any { it.startsWith("splice: waiting for") }, lines.joinToString("\n"))
    }

    @Test
    fun `--now stops at once with a compaction in flight`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(compactReads = Int.MAX_VALUE, compact = true, ageMs = 130_000)
        val stopped = try {
            restart(tmp).stopIfRunning(daemon.port, emptyList(), waitForCompactions = false)
        } finally {
            daemon.server.stop(0)
        }
        assertTrue(stopped, lines.joinToString("\n"))
        assertEquals(true, daemon.turnListedAtShutdown, "--now does not wait")
        assertFalse(lines.any { it.startsWith("splice: waiting for") }, lines.joinToString("\n"))
    }

    @Test
    fun `a compaction past the client's 600 s is not waited for`(@TempDir tmp: Path) {
        val daemon = FakeDaemon(compactReads = Int.MAX_VALUE, compact = true, ageMs = 600_000)
        val stopped = try {
            restart(tmp).stopIfRunning(daemon.port, emptyList())
        } finally {
            daemon.server.stop(0)
        }
        assertTrue(stopped, lines.joinToString("\n"))
        assertEquals(true, daemon.turnListedAtShutdown)
        assertFalse(lines.any { it.startsWith("splice: waiting for") }, lines.joinToString("\n"))
    }

    @Test
    fun `new compactions arriving during the wait cannot hold it past its cap`() {
        val reads = AtomicInteger()
        val wait = CompactionWait(
            output = { lines += it },
            inflight = { InflightRead.Count(1, listOf(CompactionSlot("claudex", reads.getAndIncrement().toLong()))) },
            pollMs = 1,
            capMs = 20,
        )
        wait.await()
        assertTrue(lines.last().endsWith("still running after 0s; restarting anyway"), lines.joinToString("\n"))
    }

    @Test
    fun `a read that cannot see the slots is said and not waited on`() {
        val unseen = InflightRead.Unknown("/api/heads did not answer")
        CompactionWait(output = { lines += it }, inflight = { unseen }).await()
        assertEquals(
            listOf(
                "splice: could not see compactions in flight (/api/heads did not answer); " +
                    "restarting without waiting",
            ),
            lines,
        )
    }
}
