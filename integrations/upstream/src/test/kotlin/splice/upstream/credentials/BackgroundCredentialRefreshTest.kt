// NEW: nonfatal prefetch failures are visible and safe; cancellation and fatal failures are not recovery.
package splice.upstream.credentials

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundCredentialRefreshTest {
    @Test
    fun `unexpected failure is logged once without credential bytes and a later launch still works`() {
        Fixture().use { fixture ->
            val failed = fixture.refresh.launch { error("synthetic-credential-canary") }
            fixture.scheduler.runCurrent()
            assertTrue(failed.isCompleted)
            assertFalse(failed.isCancelled)
            assertTrue(fixture.escaped.isEmpty())
            assertEquals(
                listOf(
                    "[synthetic-auth] background refresh failed: failure (message withheld: it may quote file bytes)",
                ),
                fixture.logs,
            )
            var succeeded = false
            fixture.refresh.launch {
                succeeded = true
                Credentials.Bearer("synthetic-current", null)
            }
            fixture.scheduler.runCurrent()
            assertTrue(succeeded, "a failed prefetch must not kill the lifecycle scope")
            assertEquals(1, fixture.logs.size)
        }
    }

    @Test
    fun `cancelling a suspended prefetch unwinds without a failure log`() {
        Fixture().use { fixture ->
            var started = false
            var unwound = false
            val job = fixture.refresh.launch {
                started = true
                try {
                    awaitCancellation()
                } finally {
                    unwound = true
                }
            }
            fixture.scheduler.runCurrent()
            assertTrue(started)
            job.cancel()
            fixture.scheduler.runCurrent()
            assertTrue(job.isCancelled)
            assertTrue(unwound)
            assertTrue(fixture.logs.isEmpty())
            assertTrue(fixture.escaped.isEmpty())
        }
    }

    @Test
    fun `a fatal error reaches the original exception handler unchanged`() {
        Fixture().use { fixture ->
            val fatal = AssertionError("synthetic fatal invariant")
            fixture.refresh.launch { throw fatal }
            fixture.scheduler.runCurrent()
            assertSame(fatal, fixture.escaped.single())
            assertTrue(fixture.logs.isEmpty())
        }
    }

    private class Fixture : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        val escaped = mutableListOf<Throwable>()
        val logs = mutableListOf<String>()
        private val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, failure -> escaped += failure },
        )
        val refresh = BackgroundCredentialRefresh(scope, "synthetic-auth") { logs += it }

        override fun close() {
            scope.cancel()
            scheduler.runCurrent()
        }
    }
}
