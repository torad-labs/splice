// NEW: V4-216 — a finished compaction answer outlives the process that kept it, and nothing the disk
// does can throw into the drive that keeps it (its finally releases the gate slot after finish()).
package splice.head.compaction

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.WallClock
import splice.head.wire.FrameRecording
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class FileCompactionRecordingsTest {

    private val dir = Files.createTempDirectory("compaction-recordings").resolve("claudex")
    private val lines = mutableListOf<String>()
    private var wallMs = 1_000_000L
    private fun store() =
        FileCompactionRecordings(dir, log = { lines += it }, now = WallClock { wallMs }, ttlMs = 60_000)

    private val frames = listOf("event: message_start\n\n", "event: content_block_delta\n\n", "event: message_stop\n\n")

    private fun kept(replay: CompactionReplay, key: String) {
        val recording = FrameRecording()
        replay.begin(key, recording)
        frames.forEach(recording::append)
        recording.complete(whole = true)
        replay.finish(key, recording, keep = true)
    }

    @Test
    fun `an answer kept by one replay is served whole by a new replay over the same directory, once`() = runTest {
        val key = checkNotNull(CompactionReplay().key("sess-1", "{}"))
        kept(CompactionReplay(store()), key)
        val file = Files.list(dir).use { it.toList().single() }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))

        val next = CompactionReplay(store())
        val restored = checkNotNull(next.lookup(key), "the next process finds the kept answer")
        assertTrue(restored.isComplete && restored.isWhole, "a kept answer is complete and whole")
        val served = mutableListOf<String>()
        assertTrue(restored.follow { served += it })
        assertEquals(frames, served, "byte-identical frames, in order")

        next.consumed(key)
        assertNull(CompactionReplay(store()).lookup(key), "a delivered replay is spent on disk too")
        assertTrue(lines.isEmpty(), lines.joinToString())
    }

    @Test
    fun `an answer not kept, or one still in flight, leaves nothing on disk`() {
        val replay = CompactionReplay(store())
        val key = checkNotNull(replay.key("sess-1", "{}"))
        val inFlight = FrameRecording().apply { append(frames.first()) }
        replay.begin(key, inFlight)
        assertTrue(!Files.exists(dir) || Files.list(dir).use { it.count() } == 0L, "in flight: nothing kept yet")
        replay.finish(key, inFlight, keep = false)
        assertNull(CompactionReplay(store()).lookup(key))
    }

    @Test
    fun `an answer past its time is not served and is swept`() {
        val key = checkNotNull(CompactionReplay().key("sess-1", "{}"))
        kept(CompactionReplay(store()), key)
        wallMs += 60_001
        assertNull(CompactionReplay(store()).lookup(key), "expired: the retry runs upstream")
        assertEquals(0L, Files.list(dir).use { it.count() }, "the expired file is gone")
    }

    @Test
    fun `an unwritable or unreadable store is reported and never throws into the drive`() {
        val key = checkNotNull(CompactionReplay().key("sess-1", "{}"))
        Files.createDirectories(dir.parent)
        Files.writeString(dir, "not a directory")
        kept(CompactionReplay(store()), key)
        assertTrue(lines.single().contains("could not keep a compaction answer"), lines.joinToString())

        Files.delete(dir)
        kept(CompactionReplay(store()), key)
        Files.list(dir).use { it.toList() }.forEach { Files.writeString(it, "{torn") }
        assertNull(CompactionReplay(store()).lookup(key), "a torn file is no answer")
        assertTrue(lines.last().contains("unreadable"), lines.joinToString())
    }
}
