// NEW (2026-09-05): a detached compaction's frame record — followable while it is still being written.
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.gateway.wire.FrameRecording

class FrameRecordingTest {

    @Test
    fun `a follower that starts after completion gets every frame in order and returns`() = runBlocking {
        val recording = FrameRecording()
        recording.append("event: message_start\n\n")
        recording.append("event: content_block_delta\n\n")
        recording.append("event: message_stop\n\n")
        recording.complete(whole = true)
        val got = mutableListOf<String>()
        withTimeout(5_000) { recording.follow { got += it } }
        assertEquals(3, got.size)
        assertTrue(got[0].startsWith("event: message_start"))
        assertTrue(recording.isComplete)
        assertEquals(3, recording.size)
    }

    @Test
    fun `a follower that starts mid-way gets the rest as it arrives and returns at completion`() = runBlocking {
        val recording = FrameRecording()
        recording.append("one")
        val got = mutableListOf<String>()
        val follower = async(Dispatchers.Default) { withTimeout(10_000) { recording.follow { got += it } } }
        delay(100) // the follower has drained "one" and is parked on the next frame
        recording.append("two")
        delay(100)
        recording.append("event: message_stop\n\n")
        recording.complete(whole = true)
        follower.await()
        assertEquals(listOf("one", "two", "event: message_stop\n\n"), got)
    }

    @Test
    fun `a recording is not complete until told so`() {
        val recording = FrameRecording()
        recording.append("event: message_start\n\n")
        assertFalse(recording.isComplete)
        recording.complete(whole = true)
        assertTrue(recording.isComplete)
    }

    /** The drive's verdict reaches a follower that was already on the recording when the drive
     *  ended: it is what LocalResponses.replay seals on (review of PR 137). */
    @Test
    fun `a follower gets the drive's verdict back - false when the recording is not a whole answer`() = runBlocking {
        val failed = FrameRecording()
        failed.append("event: message_start\n\n")
        val follower = async(Dispatchers.Default) { withTimeout(10_000) { failed.follow { } } }
        delay(100)
        failed.complete(whole = false)
        assertFalse(follower.await(), "an abandoned drive leaves no whole answer")
        assertFalse(failed.isWhole)

        val whole = FrameRecording()
        whole.append("event: message_stop\n\n")
        whole.complete(whole = true)
        assertTrue(whole.follow { }, "a clean terminal is a whole answer")
        assertTrue(whole.isWhole)
    }
}
