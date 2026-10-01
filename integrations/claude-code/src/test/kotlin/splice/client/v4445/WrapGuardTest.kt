// NEW: V4-445 — the daemon's half of "stays wrapped across updates": WrapGuard reconciles once at start and when
// the bin directory changes. Real filesystem events on a real temp directory, no fake watch: the property under
// test is that the updater's rename is SEEN and answered, so each test waits for the result it names, not for a
// clock.
package splice.client.v4445

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.wrap.WrapGuard
import splice.client.wrap.WrapResult
import splice.client.wrap.WrapRig
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WrapGuardTest {

    private val lines = CopyOnWriteArrayList<String>()
    private val rewrapped = CountDownLatch(1)

    private fun guard(rig: WrapRig): WrapGuard = WrapGuard(
        head = rig.head,
        binDir = rig.bin,
        log = { line ->
            lines += line
            if ("claude now launches" in line) rewrapped.countDown()
        },
        tickSeconds = TICK_SECONDS,
    )

    @Test
    fun `an update that lands while the guard watches is wrapped again, by the event and not the tick`(
        @TempDir home: Path,
    ) {
        val rig = WrapRig(home).also { it.linkCmdToReal() }
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        guard(rig).use { guard ->
            guard.start()
            val next = rig.update("2.1.292")
            assertTrue(rewrapped.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS), "not wrapped again: $lines")
            assertEquals(rig.shim.toRealPath(), rig.cmd.toRealPath())
            assertEquals(next.toRealPath().toString(), rig.head.realBinaryPath())
        }
    }

    @Test
    fun `an update that landed while the daemon was down is wrapped again at start`(@TempDir home: Path) {
        val rig = WrapRig(home).also { it.linkCmdToReal() }
        assertTrue(rig.head.wrap() is WrapResult.Ok)
        val next = rig.update("2.1.293")

        guard(rig).use { guard ->
            guard.start()
            assertTrue(rewrapped.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS), "not wrapped again: $lines")
            assertEquals(next.toRealPath().toString(), rig.head.realBinaryPath())
        }
    }

    @Test
    fun `an unwrapped home is left alone and says nothing`(@TempDir home: Path) {
        val rig = WrapRig(home).also { it.linkCmdToReal() }
        guard(rig).use { guard ->
            guard.start()
            rig.update("2.1.294")
            assertTrue(!rewrapped.await(SETTLE_SECONDS, TimeUnit.SECONDS), "wrapped a home that never was: $lines")
            assertEquals(rig.versions.resolve("2.1.294"), rig.cmd.toRealPath())
        }
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun `a bin directory that does not exist yet is retried on the tick, never fatal`(@TempDir home: Path) {
        val rig = WrapRig(home).also { it.linkCmdToReal() }
        val missing = home.resolve("no-bin-yet")
        val guard = WrapGuard(rig.head, missing, { lines += it }, tickSeconds = 1)
        guard.use {
            it.start()
            assertTrue(waitFor { lines.any { line -> "cannot watch $missing" in line } }, "no line named it: $lines")
        }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EVENT_WAIT_SECONDS)
        val latch = CountDownLatch(1)
        while (System.nanoTime() < deadline && !condition()) latch.await(POLL_MS, TimeUnit.MILLISECONDS)
        return condition()
    }
}

private const val TICK_SECONDS = 30L
private const val EVENT_WAIT_SECONDS = 10L
private const val SETTLE_SECONDS = 2L
private const val POLL_MS = 50L
