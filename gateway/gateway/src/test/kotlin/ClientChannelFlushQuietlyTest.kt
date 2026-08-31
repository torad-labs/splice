// DR-93 (redo): the turn finally must stay QUIET when the dead-socket flush itself throws — a
// throwing finally replaces the primary outcome (the turn's real failure) or the in-flight
// CancellationException. These arms pin ClientChannel.flushQuietly, the only sanctioned
// turn-cleanup flush (the kt-turn-finally-flush-quietly wall keeps head/ off the raw
// coalesced.flush()). Both dead-socket types are pinned: IOException from the engine write and
// IllegalStateException from a closed channel — the latter ESCAPED the pre-redo wrapper
// (runCatchingCancellable deliberately does not catch it). Cancellation must still propagate:
// the flush runs inside the cancelled coroutine's own unwind.
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.ImmediateSseWriter
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class ClientChannelFlushQuietlyTest {

    private fun channel(flushRaw: () -> Unit) = ClientChannel(
        ImmediateSseWriter(writeRaw = { _ -> }, flushRaw = { flushRaw() }),
        Mutex(),
        AtomicBoolean(false),
    )

    @Test
    fun `a closed-channel IllegalStateException is contained - the primary outcome stands`() {
        var flushes = 0
        val ch = channel {
            flushes += 1
            error("Channel is already closed") // error() throws the closed-channel type: ISE
        }
        ch.flushQuietly() // a throw here would replace the turn's outcome inside the finally
        assertEquals(1, flushes, "the flush must actually be attempted, not skipped")
    }

    @Test
    fun `a dead-socket IOException is contained`() {
        val ch = channel { throw IOException("Broken pipe") }
        ch.flushQuietly()
    }

    @Test
    fun `coroutine cancellation still propagates through the quiet flush`() {
        val cancel = CancellationException("turn cancelled")
        val ch = channel { throw cancel }
        val thrown = assertThrows(CancellationException::class.java) { ch.flushQuietly() }
        assertEquals(cancel, thrown)
    }

    @Test
    fun `a healthy flush flows through untouched`() {
        var flushes = 0
        channel { flushes += 1 }.flushQuietly()
        assertEquals(1, flushes)
    }
}
