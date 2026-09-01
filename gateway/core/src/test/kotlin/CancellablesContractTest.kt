// DR-93 (drift-repair): TurnStreamer's finally-flush wrap composes exactly two properties of this
// combinator — an I/O failure becomes a discardable Result instead of replacing the in-flight
// exception, and coroutine cancellation is NEVER captured (it must keep propagating through the
// finally). Neither property was pinned anywhere; a future broadening (catch Throwable) or
// narrowing (drop IOException) would silently change every best-effort call site at once. There is
// no TurnStreamer-level arm: that finally is reachable only through a real engine socket (ledger
// DR-93 note has the reachability analysis). DR-93 redo: the finally is enforced structurally
// instead — the kt-turn-finally-flush-quietly wall forbids a raw coalesced.flush() in head/, and
// ClientChannelFlushQuietlyTest pins the quiet-flush behavior; these arms stay the contract floor.
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

    // DR-93 redo: the CLEANUP variant. A closed write channel throws IllegalStateException, which
    // the request-path combinator deliberately lets escape — in a finally that escape replaces the
    // primary outcome, so runCatchingCleanup captures it. CancellationException is an
    // IllegalStateException SUBTYPE: the guard must win over the widened catch, or cleanup inside
    // a cancelled coroutine's unwind would eat the cancellation.
    @Test
    fun `cleanup captures a closed-channel IllegalStateException`() {
        val dead = IllegalStateException("Channel is already closed")
        val result = Cancellables.runCatchingCleanup { throw dead }
        assertSame(dead, result.exceptionOrNull())
    }

    @Test
    fun `cleanup still lets coroutine cancellation propagate past the widened catch`() {
        val cancel = CancellationException("turn cancelled")
        val thrown = runCatching {
            Cancellables.runCatchingCleanup { throw cancel }
        }.exceptionOrNull()
        assertSame(cancel, thrown)
    }

    @Test
    fun `cleanup captures an IO failure like the request-path combinator`() {
        val boom = IOException("broken pipe at flush")
        assertSame(boom, Cancellables.runCatchingCleanup { throw boom }.exceptionOrNull())
    }
}
