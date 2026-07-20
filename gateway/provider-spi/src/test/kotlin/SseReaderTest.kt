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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import splice.spi.SseFrameTooLargeException
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

    // Restructured for G12 (WHATWG blank-line dispatch): the property under test is a data VALUE
    // split across a chunk boundary (`a` + `b"}` -> `ab`), but the old fixture relied on per-line
    // dispatch — two contiguous data lines now join into one event and never dispatch without a blank
    // line. Blank-line terminators restore two events; the split-value carry is still what's exercised.
    @Test
    fun `partial line carries across chunks`() {
        val (events, _) = runReader(
            "data: {\"v\":\"a".toByteArray(),
            "b\"}\n\ndata: {\"v\":\"c\"}\n\n".toByteArray(),
        )
        assertEquals(listOf("ab", "c"), events)
    }

    // Restructured for G12 (WHATWG blank-line dispatch): the old fixture crammed [DONE]/empty/
    // malformed/comment/ok into ONE event with no blank-line separators — that only ever parsed
    // under the pre-WHATWG per-line-dispatch model. Each case is now its own blank-line-terminated
    // event; the observable outcome (only "ok" survives) is unchanged.
    @Test
    fun `done empty malformed and non-data lines are skipped`() {
        val (events, _) = runReader(
            (
                "data: [DONE]\n\n" +
                    "data: \n\n" +
                    "data: {not-json}\n\n" +
                    ": keepalive comment\n\n" +
                    "data: {\"v\":\"ok\"}\n\n"
                ).toByteArray(),
        )
        assertEquals(listOf("ok"), events)
    }

    // Restructured for G12 (WHATWG blank-line dispatch): the two data lines must be SEPARATE
    // blank-line-terminated events — under WHATWG, contiguous data lines join into one event, so the
    // old fixture would have produced a single malformed event. Observable outcome (onMalformed sees
    // "{not-json}", "ok" still emits) is unchanged.
    @Test
    fun `onMalformed fires with the raw payload for a bad frame, valid frames still emit`() {
        val (events, _, malformed) = runReader(
            ("data: {not-json}\n\n" + "data: {\"v\":\"ok\"}\n\n").toByteArray(),
        )
        assertEquals(listOf("ok"), events)
        assertEquals(listOf("{not-json}"), malformed)
    }

    // REGRESSION (claude-kimi empty-200, 2026-07-18): api.kimi.com/coding emits spec-valid SSE
    // WITHOUT the space after the colon (`data:{…}`). The old `"data: "` prefix match dropped
    // every kimi frame — 13KB of SSE in, zero events out, "stream ended without a terminal event".
    // Restructured for G12 (WHATWG blank-line dispatch): [DONE]/empty/spaced are now SEPARATE
    // blank-line-terminated events (they were crammed into one, valid only under per-line dispatch);
    // the no-space-after-colon parse and the [DONE]/empty skips are still exactly what's exercised.
    @Test
    fun `data lines without space after colon parse (kimi wire format)`() {
        val (events, _) = runReader(
            (
                "event:message_start\n" +
                    "data:{\"v\":\"kimi\"}\n\n" +
                    "data:[DONE]\n\n" +
                    "data:\n\n" +
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
        sseJsonEvents(channel, onRawText = {
            captured.append(it)
            true
        }).toList()
        assertEquals("<html><body>Unauthorized</body></html>", captured.toString())
    }

    @Test
    fun `raw observer can detach after its diagnostic prefix is full`() = runTest {
        val channel = ByteChannel()
        var calls = 0
        launch {
            repeat(4) {
                channel.writeFully("not-sse-$it\n".toByteArray())
                channel.flush()
            }
            channel.close(null)
        }
        sseJsonEvents(channel, onRawText = {
            calls++
            false
        }).toList()
        assertEquals(1, calls)
    }

    @Test
    fun `unterminated line is rejected at the configured safety limit`() = runTest {
        val channel = ByteReadChannel("x".repeat(64))
        assertThrows<SseFrameTooLargeException> {
            sseJsonEvents(channel, maxLineChars = 16).toList()
        }
    }

    @Test
    fun `multi-line event is rejected at the configured safety limit`() = runTest {
        val channel = ByteReadChannel("data: 12345678\ndata: 90\n\n")
        assertThrows<SseFrameTooLargeException> {
            sseJsonEvents(channel, maxLineChars = 64, maxEventChars = 8).toList()
        }
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

    // G12 (WHATWG HTML §9.2 event-stream assembly): table-driven coverage of the behaviors the old
    // incident-driven parser mishandled — multi-line data joined with LF, lone-CR terminators, a CRLF
    // split exactly across a chunk boundary, a leading BOM, and discard-pending-at-EOF. Each row is
    // (name, chunks, expected event "v" values); reuses runReader so it exercises the real read loop.
    @ParameterizedTest(name = "{0}")
    @MethodSource("whatwgCases")
    fun `whatwg event-stream assembly`(name: String, chunks: List<ByteArray>, expected: List<String>) {
        val (events, _) = runReader(*chunks.toTypedArray())
        assertEquals(expected, events, name)
    }

    private companion object {
        const val TORN_CHANNEL_TIMEOUT_MS = 5_000L

        @JvmStatic
        fun whatwgCases(): List<Arguments> = listOf(
            // two data lines each holding a JSON fragment invalid alone, joined with LF into one event
            Arguments.of(
                "multi-line data joined with LF merges to one JSON event",
                listOf("data: {\"v\":\ndata: \"merged\"}\n\n".toByteArray()),
                listOf("merged"),
            ),
            // lone CR as BOTH the line terminator and (a second CR) the blank-line event terminator
            Arguments.of(
                "lone-CR terminates both line and event, two independent events",
                listOf("data: {\"v\":\"one\"}\r\rdata: {\"v\":\"two\"}\r\r".toByteArray()),
                listOf("one", "two"),
            ),
            // the CR ends chunk 1, the LF opens chunk 2 — pendingCR must carry the CRLF across the edge
            Arguments.of(
                "CRLF split exactly across two chunks yields exactly one event",
                listOf("data: {\"v\":\"split\"}\r".toByteArray(), "\n\r\n".toByteArray()),
                listOf("split"),
            ),
            Arguments.of(
                "leading BOM on the first chunk is stripped and the event parses",
                listOf("\uFEFFdata: {\"v\":\"bom\"}\n\n".toByteArray()),
                listOf("bom"),
            ),
            // Deliberate behavior change from the old per-line-dispatch code (WHATWG discard-pending-
            // at-EOF): a complete data line with NO trailing blank line before close is an INCOMPLETE
            // event and must not emit — a torn connection's trailing fragment can never masquerade as
            // a finished frame (AGENTS.md L3, honest failures; this is what the old code got wrong).
            Arguments.of(
                "pending buffer discarded at EOF, no trailing blank line emits nothing",
                listOf("data: {\"v\":\"lost\"}\n".toByteArray()),
                emptyList<String>(),
            ),
            // an empty data line contributes nothing, locking in the dataBuffer-emptiness simplification
            Arguments.of(
                "interleaved empty and non-empty data in one event ignores the empty line",
                listOf("data: \ndata: {\"v\":\"ok\"}\n\n".toByteArray()),
                listOf("ok"),
            ),
        )
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
