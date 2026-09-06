// NEW (2026-09-06): the pinger's frames are frames, never model content. This is the whole safety
// argument for putting a visible status line on the wire, and it is a differential property: the
// SAME bytes must count one way through the model's write port and another through the pinger's.
//
// Three things read content_frames_out and first_delta, and all three break if splice's own line is
// counted as the model's: the idle watchdog picks its tier from content_frames_out (a silent Astra
// turn would be judged against the 180 s mid-output cap instead of the 300 s first-output one, and
// reaped healthy), G5 gates pre-content reissue on it (a retryable torn stream would degrade to a
// raw api_error), and first_delta is the instrument every latency diagnosis on this proxy starts
// from. The exclusion is carried by the PORT, so there is no frame to recognise and no window.
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.gateway.wire.ClientChannel
import splice.gateway.wire.FrameRecording
import splice.gateway.wire.ImmediateSseWriter
import java.util.concurrent.atomic.AtomicBoolean

class ClientChannelProgressTest {

    private val clock = ElapsedClock { 0L }

    /** A thinking delta — byte-shaped exactly as the wire writes one, so the two ports are compared
     *  on identical input and only the port differs. */
    private val delta = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\"," +
        "\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"x\"}}\n\n"

    private fun channel(recording: FrameRecording? = null) = ClientChannel(
        ImmediateSseWriter(writeRaw = { }, flushRaw = {}),
        Mutex(),
        AtomicBoolean(false),
        recording = recording,
    )

    @Test
    fun `a status line counts as a frame and as bytes, and as no model output at all`() {
        val perf = TurnPerf()
        channel().timedProgressWrite(delta, perf, clock)
        val snap = perf.snapshot()

        assertEquals(1L, snap.counters[PerfKeys.FRAMES_OUT], "it did reach the socket")
        assertEquals(delta.length.toLong(), snap.counters[PerfKeys.BYTES_OUT], "and it cost those bytes")
        assertNull(
            snap.counters[PerfKeys.CONTENT_FRAMES_OUT],
            "the watchdog tier and G5's reissue probe read this: splice's own line is not the client seeing output",
        )
        assertFalse(
            PerfKeys.FIRST_DELTA in snap.marks,
            "first_delta is when the MODEL first reached the client, and every latency diagnosis reads it",
        )
    }

    @Test
    fun `the identical frame through the model's port does count, so the exclusion is the port and not the bytes`() {
        val perf = TurnPerf()
        channel().timedClientWrite(delta, perf, clock)
        val snap = perf.snapshot()

        assertEquals(1L, snap.counters[PerfKeys.CONTENT_FRAMES_OUT])
        assertTrue(PerfKeys.FIRST_DELTA in snap.marks)
    }

    @Test
    fun `a status line is recorded like any other frame, so a detached compaction replays what its client saw`() {
        val recording = FrameRecording()
        channel(recording).timedProgressWrite(delta, TurnPerf(), clock)

        val replayed = mutableListOf<String>()
        recording.complete(whole = true)
        kotlinx.coroutines.runBlocking { recording.follow { replayed += it } }
        assertEquals(listOf(delta), replayed)
    }
}
