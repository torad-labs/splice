// NEW (G8): AuthProbeLoop state machine + timer wiring. Fake RefreshableAuthProvider test double
// with mutable credsOk/refreshOk flags and call counters — behavior asserted directly via
// probeOnce() (no real delay needed) except for the one timer-wiring test, which proves
// start()/stop() actually schedule/cancel on intervalMs.
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.AuthProbeLoop
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.spi.Ticker
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AuthProbeLoopTest {

    /** credentials()/refresh() independently toggle-able and counted; refreshThrows simulates a
     *  transient probe-tick failure the loop must survive. */
    private class FakeAuth(
        @Volatile var credsOk: Boolean = true,
        @Volatile var refreshOk: Boolean = true,
        @Volatile var refreshThrows: Boolean = false,
    ) : RefreshableAuthProvider {
        val credentialsCalls = AtomicInteger(0)
        val refreshCalls = AtomicInteger(0)

        override suspend fun credentials(): Credentials? {
            credentialsCalls.incrementAndGet()
            return if (credsOk) Credentials.ApiKey("k") else null
        }

        override suspend fun refresh(): Credentials? {
            refreshCalls.incrementAndGet()
            if (refreshThrows) throw IOException("upstream refresh failed")
            return if (refreshOk) Credentials.ApiKey("k") else null
        }

        override suspend fun describe(): AuthDescription = AuthDescription(present = credsOk, kind = "fake")
    }

    private fun logs() = mutableListOf<String>()

    @Test
    fun `credentials keeps succeeding across N ticks - no log line ever, refresh never called`() = runTest {
        val auth = FakeAuth(credsOk = true)
        val lines = logs()
        val loop = AuthProbeLoop("head1", auth, log = { lines += it })
        repeat(5) { loop.probeOnce() }
        assertTrue(lines.isEmpty())
        assertEquals(0, auth.refreshCalls.get())
    }

    @Test
    fun `credentials null on tick 2 after a healthy tick 1 - one transition line, refresh called once`() = runTest {
        val auth = FakeAuth(credsOk = true, refreshOk = false)
        val lines = logs()
        val loop = AuthProbeLoop("head1", auth, log = { lines += it })
        loop.probeOnce() // tick 1: healthy
        auth.credsOk = false
        loop.probeOnce() // tick 2: unhealthy
        assertEquals(listOf("[head1][auth-probe] health healthy -> unhealthy\n"), lines)
        assertEquals(1, auth.refreshCalls.get())
    }

    @Test
    fun `credentials null and refresh succeeds - health computed off refresh result, no redundant credentials call`() =
        runTest {
            val auth = FakeAuth(credsOk = false, refreshOk = true)
            val lines = logs()
            val loop = AuthProbeLoop("head1", auth, log = { lines += it })
            loop.probeOnce()
            assertEquals(1, auth.credentialsCalls.get())
            assertEquals(1, auth.refreshCalls.get())
            // first tick resolves healthy (via refresh) -> no transition/initial-unhealthy line
            assertTrue(lines.isEmpty())
        }

    @Test
    fun `first tick already unhealthy logs initial health check, not a transition, and still calls refresh`() =
        runTest {
            val auth = FakeAuth(credsOk = false, refreshOk = false)
            val lines = logs()
            val loop = AuthProbeLoop("head1", auth, log = { lines += it })
            loop.probeOnce()
            assertEquals(listOf("[head1][auth-probe] initial health check: unhealthy\n"), lines)
            assertEquals(1, auth.refreshCalls.get())
        }

    @Test
    fun `unhealthy to healthy across ticks logs exactly one recovery line`() = runTest {
        val auth = FakeAuth(credsOk = false, refreshOk = false)
        val lines = logs()
        val loop = AuthProbeLoop("head1", auth, log = { lines += it })
        loop.probeOnce() // initial: unhealthy
        auth.credsOk = true
        loop.probeOnce() // recovered
        assertEquals(
            listOf(
                "[head1][auth-probe] initial health check: unhealthy\n",
                "[head1][auth-probe] health unhealthy -> healthy\n",
            ),
            lines,
        )
    }

    @Test
    fun `api-key-shaped fake never doubles credentials-equivalent calls per unhealthy tick`() = runTest {
        // ApiKeyAuthProvider.refresh() == credentials(): a harmless re-read. Model that shape here
        // by keeping refreshOk in lockstep with credsOk, and assert exactly one call to each SPI
        // method per tick — the probe must not read credentials() twice.
        val auth = FakeAuth(credsOk = false, refreshOk = false)
        val loop = AuthProbeLoop("head1", auth, log = {})
        loop.probeOnce()
        assertEquals(1, auth.credentialsCalls.get())
        assertEquals(1, auth.refreshCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `timer wiring - start ticks immediately then every intervalMs, stop halts further ticks`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val auth = FakeAuth(credsOk = true)
        val loop = AuthProbeLoop("head1", auth, intervalMs = 1000L, log = {})
        loop.start(scope)
        scope.advanceTimeBy(1) // let the immediate first tick run
        assertEquals(1, auth.credentialsCalls.get())
        repeat(3) {
            scope.advanceTimeBy(1000)
        }
        assertEquals(4, auth.credentialsCalls.get())
        loop.stop()
        scope.advanceTimeBy(5000)
        assertEquals(4, auth.credentialsCalls.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a probe tick that throws a non-cancellation exception does not kill the loop`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val auth = FakeAuth(credsOk = false, refreshOk = false, refreshThrows = true)
        val lines = logs()
        val loop = AuthProbeLoop("head1", auth, intervalMs = 1000L, log = { lines += it })
        loop.start(scope)
        scope.advanceTimeBy(1) // tick 1 throws (credentials() null -> refresh() throws IOException)
        scope.advanceTimeBy(1000) // tick 2
        scope.advanceTimeBy(1000) // tick 3
        assertEquals(3, auth.credentialsCalls.get())
        assertTrue(lines.all { it.contains("probe tick threw") })
        loop.stop()
    }

    @Test
    fun `a loop-killing throwable restarts the probe under the budget - SH-04`() = runTest {
        // IllegalStateException is outside runCatchingCancellable's catch list: pre-fix the
        // coroutine died silently and start() refused to re-arm — zero further ticks, ever.
        val auth = object : RefreshableAuthProvider {
            val calls = AtomicInteger(0)
            override suspend fun credentials(): Credentials? {
                if (calls.incrementAndGet() == 1) check(false) { "provider invariant blew up" }
                return Credentials.ApiKey("k")
            }
            override suspend fun refresh(): Credentials? = credentials()
            override suspend fun describe() = AuthDescription(present = true, kind = "fake")
        }
        val logs = mutableListOf<String>()
        val loop = AuthProbeLoop("codex", auth, intervalMs = 1_000, log = logs::add, clock = { 0L })
        // production runs probes under a SupervisorJob (Daemon.probeScope) — mirror it, or the
        // dying child cancels the test scope itself.
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }, // JVM default-handler analog
        )
        loop.start(scope)
        advanceTimeBy(3_500)
        loop.stop()
        assertTrue(logs.any { it.contains("loop died") && it.contains("restarting (1/5)") }, "$logs")
        assertTrue(auth.calls.get() >= 2, "the restarted loop must tick again, saw ${auth.calls.get()}")
    }

    @Test
    fun `stop during restart handoff prevents probe resurrection`() = runTest {
        val calls = AtomicInteger(0)
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials? {
                if (calls.incrementAndGet() == 1) error("provider invariant blew up")
                return Credentials.ApiKey("k")
            }
            override suspend fun refresh(): Credentials? = credentials()
            override suspend fun describe() = AuthDescription(present = true, kind = "fake")
        }
        lateinit var loop: AuthProbeLoop
        var stopIssued = false
        loop = AuthProbeLoop(
            key = "codex",
            auth = auth,
            intervalMs = 1_000,
            log = { line ->
                if (line.contains("restarting")) {
                    stopIssued = true
                    loop.stop()
                }
            },
            clock = { 0L },
            ticker = Ticker { false },
        )
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> },
        )

        loop.start(scope)
        testScheduler.advanceUntilIdle()

        assertTrue(stopIssued, "the test must stop exactly inside the restart handoff")
        assertEquals(1, calls.get(), "stop must prevent a replacement probe from launching")
    }

    @Test
    fun `restart budget exhaustion announces permanently down - SH-04`() = runTest {
        val auth = object : RefreshableAuthProvider {
            val calls = AtomicInteger(0)
            override suspend fun credentials(): Credentials? {
                calls.incrementAndGet()
                check(false) { "always dies" }
                return null
            }
            override suspend fun refresh(): Credentials? = credentials()
            override suspend fun describe() = AuthDescription(present = true, kind = "fake")
        }
        val logs = mutableListOf<String>()
        // fixed clock: every death lands in ONE rolling window, so the 6th exhausts the budget
        val loop = AuthProbeLoop("codex", auth, intervalMs = 1_000, log = logs::add, clock = { 0L })
        val scope = kotlinx.coroutines.CoroutineScope(
            StandardTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }, // JVM default-handler analog
        )
        loop.start(scope)
        advanceTimeBy(20_000)
        loop.stop()
        assertEquals(5, logs.count { it.contains("restarting (") }, "$logs")
        assertEquals(1, logs.count { it.contains("permanently down") }, "$logs")
        assertEquals(6, auth.calls.get(), "5 restarts + the original = 6 first-ticks, then silence")
    }
}
