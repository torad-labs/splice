// NEW: 2026-09-23 — pins the three claims TestPorts makes: its ports sit below the ephemeral range and
// bind, a port something holds is never handed out, and a reservation holds ACROSS JVMs. The last is
// the one the daemon-boot tests depend on (parallel Gradle workers), so it is proven with a real
// second JVM holding the lock, not with a second lock in this one.
package splice.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TestPortsTest {

    private val loopback = InetAddress.getLoopbackAddress()

    @Test
    fun `a reserved port sits below the ephemeral range and binds`() {
        val port = TestPorts.reserve()
        val ephemeralStart = File("/proc/sys/net/ipv4/ip_local_port_range").takeIf { it.canRead() }
            ?.readText()?.trim()?.split(Regex("\\s+"))?.first()?.toInt() ?: 32_768
        assertTrue(port in 20_000 until ephemeralStart, "port $port is not below the ephemeral range")
        ServerSocket().use { it.bind(InetSocketAddress(loopback, port)) }
    }

    @Test
    fun `no port is handed out twice in one run`() {
        val ports = List(50) { TestPorts.reserve() }
        assertEquals(ports.size, ports.toSet().size, "a port was reserved twice: $ports")
    }

    @Test
    fun `a port this JVM holds, or something is bound to, is skipped`() {
        val held = TestPorts.reserve()
        assertThrows(IllegalStateException::class.java) { TestPorts.reserveFrom(listOf(held).iterator()) }
        ServerSocket(0, 1, loopback).use { listener ->
            assertThrows(IllegalStateException::class.java) {
                TestPorts.reserveFrom(listOf(listener.localPort).iterator())
            }
        }
    }

    @Test
    fun `a port another JVM holds is skipped, and reserved once that JVM lets go`() {
        // Drawn BELOW the range reserve() uses, so no other test in this JVM can already hold it, and
        // bindable now, so the positive half below can only fail on the lock.
        val port = generateSequence { Random.nextInt(10_000, 20_000) }.first { candidate ->
            runCatching { ServerSocket().use { it.bind(InetSocketAddress(loopback, candidate)) } }.isSuccess
        }
        val child = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp",
            System.getProperty("java.class.path"),
            ForeignLockHolder::class.java.name,
            TestPorts.lockFile(port).toString(),
        ).redirectErrorStream(true).start()
        try {
            val said = child.inputStream.bufferedReader().readLine()
            assertEquals("locked", said, "the second JVM did not take the lock")
            assertThrows(IllegalStateException::class.java) { TestPorts.reserveFrom(listOf(port).iterator()) }
        } finally {
            child.outputStream.close()
            child.waitFor(CHILD_EXIT_SECONDS, TimeUnit.SECONDS)
            child.destroyForcibly()
        }
        // The positive half: the same port, once released, IS reserved — so the refusal above was the
        // other JVM's lock and not a port that happened to be taken.
        assertEquals(port, TestPorts.reserveFrom(listOf(port).iterator()))
    }
}

/** Run in a second JVM by the test above: take a port's lock file the way TestPorts does, say so, and
 *  hold it until stdin closes. */
object ForeignLockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = Path.of(args[0])
        Files.createDirectories(file.parent)
        val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        checkNotNull(channel.tryLock()) { "$file is already locked" }
        println("locked")
        System.out.flush()
        System.`in`.read()
    }
}

private const val CHILD_EXIT_SECONDS = 10L
