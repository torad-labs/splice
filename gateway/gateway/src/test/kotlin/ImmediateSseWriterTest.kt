// Pins the flush-per-frame contract (the lull-bug fix, HeadServerLoadTest 2026-07-18): every
// written frame is pushed to the client immediately — a frame must NEVER wait for the next write
// to become visible. flush() is a safe final push for the turn's finally-block.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.gateway.wire.ImmediateSseWriter

class ImmediateSseWriterTest {

    @Test
    fun `every frame is written and flushed in the same call`() {
        val written = mutableListOf<String>()
        var flushes = 0
        val w = ImmediateSseWriter(
            writeRaw = { written.add(it) },
            flushRaw = { flushes++ },
        )
        w.write("a")
        assertEquals(1, flushes)
        w.write("bb")
        assertEquals(2, flushes)
        w.write("ccc")
        assertEquals(3, flushes)
        assertEquals(listOf("a", "bb", "ccc"), written)

        // finally-block belt-and-suspenders: one more push, never a lost frame.
        w.flush()
        assertEquals(4, flushes)
    }
}
