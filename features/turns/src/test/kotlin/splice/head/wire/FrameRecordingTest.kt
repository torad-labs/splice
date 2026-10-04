// NEW (2026-09-05): a detached compaction's frame record — followable while it is still being written.
package splice.head.wire

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapJson
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

// why: bounded ordinary allocation pressure exercises GC-owned release without an explicit GC call.
private const val OWNER_PRESSURE_BYTES = 1024 * 1024

class FrameRecordingTest {
    @Volatile private var ownerPressure: ByteArray? = null

    @Test
    fun `static frame literals cannot retain a discarded recording's reservation`() {
        refundsStaticFrame("static-frame")
    }

    @Test
    fun `interned frame content cannot retain a discarded recording's reservation`() {
        refundsStaticFrame("interned-frame".intern())
    }

    @Test
    fun `empty frames release their actual copied string owner`() {
        refundsStaticFrame("")
    }

    @Test
    fun `an escaped frame keeps its string metadata after the recording is collected`() {
        val heap = HeapBudget(Long.MAX_VALUE, 4096)
        val (recording, escaped) = escapedFrame(heap)
        awaitUntil { recording.refersTo(null) && heap.available.value >= heap.limitBytes - HeapJson.text(escaped) }
        assertTrue(
            heap.available.value <= heap.limitBytes - HeapJson.text(escaped),
            "escaped string metadata belongs to the frame, not the discarded recording",
        )
        Reference.reachabilityFence(escaped)
    }

    private fun refundsStaticFrame(text: String) {
        val heap = HeapBudget(Long.MAX_VALUE, 4096)
        val recording = discardedRecording(heap, text)
        awaitUntil { recording.refersTo(null) && heap.available.value == heap.limitBytes }
        Reference.reachabilityFence(text)
    }

    private fun discardedRecording(heap: HeapBudget, frame: String): WeakReference<FrameRecording> =
        WeakReference(FrameRecording(heap).also { it.append(frame) })

    private fun escapedFrame(heap: HeapBudget): Pair<WeakReference<FrameRecording>, String> {
        val recording = FrameRecording(heap)
        recording.append("escaped-frame")
        return WeakReference(recording) to recording.frames().single()
    }

    private fun awaitUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!done()) {
            check(System.nanoTime() < deadline) { "frame owner did not settle before its deadline" }
            ownerPressure = ByteArray(OWNER_PRESSURE_BYTES)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
    }

    @Test
    fun `failed append preserves prior frames and wakes followers with an honest verdict`() = runBlocking {
        val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = 140)
        val recording = FrameRecording(heap)
        recording.append("one")
        assertThrows(HeapCapacityException::class.java) { recording.append("two") }
        recording.complete(whole = false)
        val received = mutableListOf<String>()
        assertFalse(recording.follow { received += it })
        assertEquals(listOf("one"), received)
        assertEquals(6L, heap.available.value, "only the temporary character array's charge was returned")
    }

    @Test
    fun `recordings across heads compete for the same reservation bytes`() {
        val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = 280)
        val first = FrameRecording(heap)
        val second = FrameRecording(heap)
        first.append("one")
        second.append("two")
        assertThrows(HeapCapacityException::class.java) { first.append("three") }
        assertThrows(HeapCapacityException::class.java) { second.append("four") }
        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    @Test
    fun `an escaped frame list reserves its copy before allocating`() {
        val heap = HeapBudget(heapLimitBytes = Long.MAX_VALUE, budgetBytes = 198)
        val recording = FrameRecording(heap)
        recording.append("one")
        val escaped = recording.frames()
        assertEquals(listOf("one"), escaped)
        assertEquals(0L, heap.available.value)
        assertThrows(HeapCapacityException::class.java) { recording.frames() }
        assertEquals(1, recording.size)
    }

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
        val drainedOne = CompletableDeferred<Unit>()
        val drainedTwo = CompletableDeferred<Unit>()
        val follower = async(Dispatchers.Default) {
            withTimeout(10_000) {
                recording.follow {
                    got += it
                    if (it == "one") drainedOne.complete(Unit)
                    if (it == "two") drainedTwo.complete(Unit)
                }
            }
        }
        drainedOne.await() // the follower has drained "one" and is parked on the next frame
        recording.append("two")
        drainedTwo.await()
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
        val drained = CompletableDeferred<Unit>()
        val follower = async(Dispatchers.Default) {
            withTimeout(10_000) { failed.follow { drained.complete(Unit) } }
        }
        drained.await()
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
