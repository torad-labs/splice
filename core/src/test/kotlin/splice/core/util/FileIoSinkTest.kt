package splice.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileIoSinkTest {
    @Test
    fun `named sinks reconcile accepted writes failures and refused work independently`(@TempDir directory: Path) {
        val raw = FileIoSink("raw_rows", WallClock { 42L })
        val aggregate = FileIoSink("aggregate_history", WallClock { 99L })
        val file = directory.resolve("row")
        assertTrue(AsyncFileIo.submitFor(file, raw) { Files.writeString(file, "record"); true })
        assertTrue(AsyncFileIo.awaitFile(file))
        assertTrue(AsyncFileIo.submitFor(directory.resolve("failed"), raw) { false })
        assertFalse(AsyncFileIo.awaitFile(directory.resolve("failed")))
        raw.reject()
        val status = raw.status()
        assertEquals("record", Files.readString(file))
        assertEquals(3L, status.observed)
        assertEquals(2L, status.enqueued)
        assertEquals(1L, status.written)
        assertEquals(1L, status.failed)
        assertEquals(1L, status.dropped)
        assertEquals(0L, status.pending)
        assertEquals(42L, status.lastSuccessEpochMs)
        assertEquals(0L, aggregate.status().observed)
    }
}
