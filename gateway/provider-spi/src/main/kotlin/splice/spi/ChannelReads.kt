// NEW: the ONE readAvailable/awaitContent walk. Three copies of it existed after HD-24 split the
// body readers out (SseDecode.readChunk, UpstreamResponse.bodyTextLimited, RequestBodyReader), and
// only the SSE one carried the 2026-07-18 fix for the 600%-CPU "connection closed mid-response"
// incident. The other two were byte-for-byte the PRE-fix shape, so the same torn-channel hot-spin
// was still reachable on the request-body and upstream-error-body paths, both of which run while a
// turn holds its InflightGate slot. Review 2026-08-28 (PR 99, comments 2 and 6).
//
// PUBLIC on purpose, unlike most of this package: :gateway consumes it across a module boundary
// (RequestBodyReader), where `internal` does not reach.
package splice.spi

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// A healthy channel never reports content it cannot deliver; a run of consecutive torn wakeups
// means the upstream is broken — end the stream honestly rather than pin a core.
internal const val MAX_SPURIOUS_WAKEUPS = 1024

public object ChannelReads {

    /**
     * Read whatever is available into [buffer]; returns byte count (> 0) or -1 at end of stream.
     *
     * On a healthy channel `readAvailable` suspends inside `awaitContent` when the buffer is empty,
     * so the guarded branch below is never reached. It exists for the TORN case — a half-closed /
     * degenerate peer where `readAvailable` returns 0 WITHOUT suspending. The naive
     * `while (readAvailable() == 0)` loop has no suspension or cancellation point there, so a turn
     * whose client already disconnected (turnJob cancelled by the pinger/watchdog) cannot exit it:
     * the coroutine hot-spins syscalls forever, pinning a core per leaked stream.
     *
     * The two guards make the loop cancellation-cooperative and impossible to hot-spin: honor
     * cancellation first, then actually WAIT on `awaitContent` (false == closed, so EOF), and bail
     * if the channel keeps claiming content it cannot deliver. Note that a `withTimeout` wrapper
     * around the caller does NOT substitute for the first guard: a timeout is delivered at a genuine
     * suspension point, which is precisely what the degenerate channel fails to provide.
     */
    public suspend fun readAvailableOrEof(channel: ByteReadChannel, buffer: ByteArray): Int {
        var spuriousWakeups = 0
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n != 0) return n // > 0 bytes, or -1 at end of stream
            // n == 0 on an open channel: readAvailable did NOT suspend (torn/half-closed peer).
            currentCoroutineContext().ensureActive() // a cancelled turn exits here, never spins
            if (!channel.awaitContent(1)) return -1 // suspends until content or close; false == closed
            // UP-005: distinguish exhaustion from the clean EOF above — a channel that keeps lying
            // about content never gets to look like a normal, successful end of stream.
            if (++spuriousWakeups >= MAX_SPURIOUS_WAKEUPS) throw SseSpuriousWakeupException(MAX_SPURIOUS_WAKEUPS)
        }
    }
}
