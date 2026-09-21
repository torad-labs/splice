// NEW: CW-1 — KeyReader from an injected InputStream. Split CSI across reads, and a
// lone ESC that must yield Escape without blocking forever.
package splice.app.cli.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Duration

class KeyReaderTest {

    @Test
    fun `arrow CSI decodes to Up Down Right Left`() {
        assertEquals(Key.Up, readBytes(27, 91, 65))
        assertEquals(Key.Down, readBytes(27, 91, 66))
        assertEquals(Key.Right, readBytes(27, 91, 67))
        assertEquals(Key.Left, readBytes(27, 91, 68))
    }

    @Test
    fun `control bytes decode to named keys`() {
        assertEquals(Key.CtrlC, readBytes(3))
        assertEquals(Key.Enter, readBytes(13))
        assertEquals(Key.Enter, readBytes(10))
        assertEquals(Key.Space, readBytes(32))
        assertEquals(Key.Backspace, readBytes(127))
        assertEquals(Key.Backspace, readBytes(8))
        assertEquals(Key.Char(97), readBytes(97))
    }

    @Test
    fun `a CSI sequence split across two reads still yields Up`() {
        val src = CountingStream(ByteArrayInputStream(byteArrayOf(27, 91, 65)))
        assertEquals(Key.Up, KeyReader(src).read())
        assertEquals(3, src.reads, "the three CSI bytes must not be consumed as one read")
    }

    @Test
    fun `a lone ESC yields Escape and does not block forever`() {
        assertTimeout(Duration.ofSeconds(1)) {
            assertEquals(Key.Escape, readBytes(27))
        }
    }

    private fun readBytes(vararg bytes: Byte): Key =
        KeyReader(ByteArrayInputStream(bytes)).read()

    private class CountingStream(private val inner: InputStream) : InputStream() {
        var reads = 0
        override fun read(): Int {
            reads += 1
            return inner.read()
        }
        override fun available(): Int = inner.available()
    }
}
