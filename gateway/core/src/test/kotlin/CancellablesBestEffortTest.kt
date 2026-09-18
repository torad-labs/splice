// NEW: pins runCatchingBestEffort's capture-then-classify contract (V4-118): CancellationException
// and Error are rethrown, everything else — including an IllegalStateException that
// runCatchingCancellable lets escape — is captured, so a best-effort post-persist step can never
// abort an already-completed login.
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.util.Cancellables
import java.io.IOException
import java.util.concurrent.CancellationException

class CancellablesBestEffortTest {

    @Test
    fun `an IllegalStateException is captured, not propagated`() {
        val boom = IllegalStateException("mint exploded")
        val result = Cancellables.runCatchingBestEffort { throw boom }
        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun `an arbitrary RuntimeException is captured, not propagated`() {
        val boom = RuntimeException("provider blew up")
        val result = Cancellables.runCatchingBestEffort { throw boom }
        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun `an IO failure is captured like the narrower forms`() {
        val boom = IOException("disk full")
        val result = Cancellables.runCatchingBestEffort { throw boom }
        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun `coroutine cancellation propagates instead of being captured`() {
        val cancel = CancellationException("client gone")
        val thrown = runCatching {
            Cancellables.runCatchingBestEffort { throw cancel }
        }.exceptionOrNull()
        assertSame(cancel, thrown)
    }

    @Test
    fun `an Error propagates instead of being captured`() {
        val fatal = AssertionError("invariant broken")
        val thrown = runCatching {
            Cancellables.runCatchingBestEffort { throw fatal }
        }.exceptionOrNull()
        assertSame(fatal, thrown)
    }
}
