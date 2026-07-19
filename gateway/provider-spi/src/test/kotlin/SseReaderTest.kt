// PORT-OF: the sseEvents pins from server/test/codex-proxy.test.mjs @ 4ca99f7 — UTF-8 split
// across chunk boundaries, partial-line carry, [DONE]/empty/malformed skip, CRLF tolerance,
// onBytes touch per raw read.
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.sseJsonEvents

class SseReaderTest {

    private fun runReader(vararg chunks: ByteArray): Triple<List<String>, Int, List<String>> {
        var touches = 0
        var texts: List<String> = emptyList()
        val malformed = mutableListOf<String>()
        runTest {
            val channel = ByteChannel()
            launch {
                for (c in chunks) {
                    channel.writeFully(c)
                    channel.flush()
                }
                channel.close(null)
            }
            texts = sseJsonEvents(channel, onBytes = { touches++ }, onMalformed = { malformed.add(it) })
                .toList()
                .map { it["v"]?.jsonPrimitive?.content ?: it.toString() }
        }
        return Triple(texts, touches, malformed)
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
    fun `onMalformed fires with the raw payload for a bad frame, valid frames still emit`() {
        val (events, _, malformed) = runReader(
            ("data: {not-json}\n" + "data: {\"v\":\"ok\"}\n\n").toByteArray(),
        )
        assertEquals(listOf("ok"), events)
        assertEquals(listOf("{not-json}"), malformed)
    }

    // REGRESSION (claude-kimi empty-200, 2026-07-18): api.kimi.com/coding emits spec-valid SSE
    // WITHOUT the space after the colon (`data:{…}`). The old `"data: "` prefix match dropped
    // every kimi frame — 13KB of SSE in, zero events out, "stream ended without a terminal event".
    @Test
    fun `data lines without space after colon parse (kimi wire format)`() {
        val (events, _) = runReader(
            (
                "event:message_start\n" +
                    "data:{\"v\":\"kimi\"}\n\n" +
                    "data:[DONE]\n" +
                    "data:\n" +
                    "data: {\"v\":\"spaced\"}\n\n"
                ).toByteArray(),
        )
        assertEquals(listOf("kimi", "spaced"), events)
    }

    @Test
    fun `crlf lines parse like lf`() {
        val (events, _) = runReader("data: {\"v\":\"crlf\"}\r\n\r\n".toByteArray())
        assertEquals(listOf("crlf"), events)
    }

    // G2 (zero-event classification): onRawText must expose the FULL decoded body — even a non-SSE
    // page with no `data:` prefix (the empty-200 dead-head shape) — reassembled across chunk edges.
    @Test
    fun `onRawText captures the full decoded body text across chunk boundaries`() = runTest {
        val channel = ByteChannel()
        val captured = StringBuilder()
        launch {
            channel.writeFully("<html><body>Unauthor".toByteArray())
            channel.flush()
            channel.writeFully("ized</body></html>".toByteArray())
            channel.flush()
            channel.close(null)
        }
        sseJsonEvents(channel, onRawText = { captured.append(it) }).toList()
        assertEquals("<html><body>Unauthorized</body></html>", captured.toString())
    }

    // REGRESSION (600%-CPU incident, 2026-07-18): a torn/half-closed upstream where readAvailable
    // returns 0 WITHOUT suspending. The old `while (readAvailable() == 0)` loop had no suspension or
    // cancellation point on that path — it hot-spun a core forever and could not be cancelled when
    // the client had already disconnected. The reader must instead TERMINATE promptly. With the old
    // code this test hangs (a real wall-clock spin runTest cannot cancel); withTimeout makes that an
    // explicit failure instead of an infinite loop.
    @Test
    fun `torn channel that never yields bytes terminates instead of hot-spinning`() = runTest {
        val emitted = withTimeout(TORN_CHANNEL_TIMEOUT_MS) {
            sseJsonEvents(TornChannel()).count()
        }
        assertEquals(0, emitted) // no frames, and — critically — it RETURNED
    }

    private companion object {
        const val TORN_CHANNEL_TIMEOUT_MS = 5_000L
    }

    /**
     * A degenerate upstream that reproduces the incident state: never closed, always claims content
     * is available, yet its read buffer is always empty — so `readAvailable` reads 0 bytes and does
     * not suspend. The real CIO client hit this on a half-closed peer; here it is deterministic.
     * Implementing ByteReadChannel is an internal Ktor API — fine for a test double.
     */
    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class TornChannel : ByteReadChannel {
        override val closedCause: Throwable? = null
        override val isClosedForRead: Boolean = false
        override val readBuffer: Source = Buffer() // always empty -> readAvailable returns 0
        override suspend fun awaitContent(min: Int): Boolean = true // lies: claims content, delivers none
        override fun cancel(cause: Throwable?) { /* test double: nothing to cancel */ }
    }
}
