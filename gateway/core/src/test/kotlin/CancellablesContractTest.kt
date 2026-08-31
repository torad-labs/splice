// DR-93 (drift-repair): TurnStreamer's finally-flush wrap composes exactly two properties of this
// combinator — an I/O failure becomes a discardable Result instead of replacing the in-flight
// exception, and coroutine cancellation is NEVER captured (it must keep propagating through the
// finally). Neither property was pinned anywhere; a future broadening (catch Throwable) or
// narrowing (drop IOException) would silently change every best-effort call site at once. There is
// no TurnStreamer-level arm: that finally is reachable only through a real engine socket (ledger
// DR-93 note has the reachability analysis).
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.util.Cancellables
import java.io.IOException
import java.util.concurrent.CancellationException

class CancellablesContractTest {

    @Test
    fun `an IO failure is captured as a Result, not propagated`() {
        val boom = IOException("broken pipe")
        val result = Cancellables.runCatchingCancellable { throw boom }
        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun `coroutine cancellation propagates instead of being captured`() {
        val cancel = CancellationException("client gone")
        val thrown = runCatching {
            Cancellables.runCatchingCancellable { throw cancel }
        }.exceptionOrNull()
        assertSame(cancel, thrown)
    }
}
