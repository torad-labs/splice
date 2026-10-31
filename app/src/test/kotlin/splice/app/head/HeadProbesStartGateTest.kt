// NEW: a head start that comes after the daemon's stop boundary is refused. Startup, cancelled by the stop, could still
// walk on to the next assembled head and start it after the stop had stopped everything, leaving a live listener under a
// released lock. The gate that closes the starts is the same one a start holds, so a start in flight finishes first.
package splice.app.head

import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.control.HeadSources
import splice.app.control.ManagedHead
import splice.app.control.UsageWarning
import splice.app.control.UsageWarningSource
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.util.LogSink
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class CountingHead(override val key: String) : Head {
    var starts = 0
    var restarts = 0
    override val label = key
    override val port = 0
    override suspend fun start() {
        starts++
    }
    override suspend fun stop() = Unit
    override suspend fun restart() {
        restarts++
    }
    override fun healthSnapshot() = HeadHealth(ok = true, running = true, port = port, version = "test")
}

/** A head whose start blocks its thread, as a start blocked in a call that cannot be cancelled does. */
private class BlockedStartHead(
    override val key: String,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : Head {
    var starts = 0
    override val label = key
    override val port = 0
    override suspend fun start() {
        starts++
        entered.countDown()
        release.await()
    }
    override suspend fun stop() = Unit
    override suspend fun restart() = Unit
    override fun healthSnapshot() = HeadHealth(ok = true, running = true, port = port, version = "test")
}

private const val WAIT_S = 30L
private const val STOP_CUT_MS = 100L

class HeadProbesStartGateTest {

    private fun managed(head: Head) = ManagedHead(
        head = head,
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "synthetic", emptyMap())
        },
        sources = HeadSources(
            usage = HeadUsageSource { UsageView(0, 0, null) },
            compact = object : HeadCompactSource {
                override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
            },
            logs = object : HeadLogSource {
                override fun tail(lines: Int) = ""
                override fun path() = "/synthetic/daemon.log"
            },
        ),
        usageWarning = UsageWarningSource { UsageWarning(warnPct = 80, warnTokens5h = 0) },
    )

    @Test
    fun `a head start after the stop boundary is refused, and an operator restart still works`() = runBlocking {
        val probes = HeadProbes()
        val scope = LifecycleScope(ProcessDispatchers().background())
        try {
            val head = CountingHead("late")
            val heads = mapOf("late" to managed(head))

            probes.closeStarts()
            probes.startDaemonHeads(heads, mutableMapOf(), scope, LogSink { })
            assertEquals(0, head.starts) { "a head was started after the daemon's stop boundary" }

            heads.getValue("late").head.restart()
            assertEquals(1, head.restarts) { "an operator restart must not go through the closed gate" }
        } finally {
            probes.stop()
            scope.cancel()
        }
    }

    @Test
    fun `before the boundary the heads start as they always did`() = runBlocking {
        val probes = HeadProbes()
        val scope = LifecycleScope(ProcessDispatchers().background())
        try {
            val head = CountingHead("early")
            probes.startDaemonHeads(mapOf("early" to managed(head)), mutableMapOf(), scope, LogSink { })
            assertEquals(1, head.starts)
        } finally {
            probes.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a stop cut short while a start holds the gate still keeps every later head from starting`() = runBlocking {
        val probes = HeadProbes()
        val scope = LifecycleScope(ProcessDispatchers().background())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val blocked = BlockedStartHead("first", entered, release)
            val later = CountingHead("second")
            val heads = linkedMapOf("first" to managed(blocked), "second" to managed(later))
            val starting = async(ProcessDispatchers().background()) {
                probes.startDaemonHeads(heads, mutableMapOf(), scope, LogSink { })
            }
            assertEquals(true, entered.await(WAIT_S, TimeUnit.SECONDS)) { "the first start never began" }

            val finished = withTimeoutOrNull(STOP_CUT_MS) { probes.closeStarts() }
            assertNull(finished) { "closeStarts must still be waiting on the start in flight" }

            release.countDown()
            starting.await()
            assertEquals(1, blocked.starts)
            assertEquals(0, later.starts) { "a head was started after the stop's deadline cut closeStarts short" }
        } finally {
            release.countDown()
            probes.stop()
            scope.cancel()
        }
    }
}
