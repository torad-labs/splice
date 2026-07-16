// PORT-OF: the sseEvents pins from server/test/codex-proxy.test.mjs @ 4ca99f7 — UTF-8 split
// across chunk boundaries, partial-line carry, [DONE]/empty/malformed skip, CRLF tolerance,
// onBytes touch per raw read.
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.sseJsonEvents

class SseReaderTest {

    private fun runReader(vararg chunks: ByteArray): Pair<List<String>, Int> {
        var touches = 0
        var texts: List<String> = emptyList()
        runTest {
            val channel = ByteChannel()
            launch {
                for (c in chunks) {
                    channel.writeFully(c)
                    channel.flush()
                }
                channel.close(null)
            }
            texts = sseJsonEvents(channel) { touches++ }
                .toList()
                .map { it["v"]?.jsonPrimitive?.content ?: it.toString() }
        }
        return texts to touches
    }

    @Test
    fun `multibyte utf8 split across chunk boundary decodes intact`() {
        val whole = "data: {\"v\":\"héllo — ünïcode\"}\n\n".toByteArray()
        // split INSIDE the é multi-byte sequence
        val cut = whole.indexOfFirst { it == 'h'.code.toByte() } + 2
        val (events, touches) = runReader(whole.copyOfRange(0, cut), whole.copyOfRange(cut, whole.size))
        assertEquals(listOf("héllo — ünïcode"), events)
        assertTrue(touches >= 1) // reads may coalesce flushed chunks — same property as Node data events
    }

    @Test
    fun `partial line carries across chunks`() {
        val (events, _) = runReader(
            "data: {\"v\":\"a".toByteArray(),
            "b\"}\ndata: {\"v\":\"c\"}\n".toByteArray(),
        )
        assertEquals(listOf("ab", "c"), events)
    }

    @Test
    fun `done empty malformed and non-data lines are skipped`() {
        val (events, _) = runReader(
            (
                "event: response.output_text.delta\n" +
                    "data: [DONE]\n" +
                    "data: \n" +
                    "data: {not-json}\n" +
                    ": keepalive comment\n" +
                    "data: {\"v\":\"ok\"}\n\n"
                ).toByteArray(),
        )
        assertEquals(listOf("ok"), events)
    }

    @Test
    fun `crlf lines parse like lf`() {
        val (events, _) = runReader("data: {\"v\":\"crlf\"}\r\n\r\n".toByteArray())
        assertEquals(listOf("crlf"), events)
    }
}
