// PORT-OF: server/src/codex/stream.mjs sseEvents @ 4ca99f7 — invariants: multi-byte-safe UTF-8
// across chunk boundaries (streaming decoder, never split a codepoint); partial last line
// carries to the next chunk; only `data:`-prefixed lines yield (the space after the colon is
// OPTIONAL per the SSE spec — kimi emits `data:{…}` bare, Anthropic/OpenAI emit `data: {…}`;
// requiring the space silently dropped every kimi frame); empty payloads and [DONE]
// skipped; malformed JSON frames skipped (never crash the stream); onBytes fires ON RAW READ
// with the chunk size (the watchdog touch + byte telemetry — never after downstream write; a
// slow client must not fake idleness). Hot-path shape: one reused decode scratch per stream
// (no per-chunk buffer allocs) and index-scanned lines (no per-line StringBuilder churn).
package splice.spi

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.runCatchingCancellable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

private val lenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// no space: SSE field syntax is `data:` + optional single space + value (WHATWG spec); the
// leading-ws trim in emitDataLine absorbs the space when present.
private const val DATA_PREFIX = "data:"
private const val DONE_SENTINEL = "[DONE]"
private const val READ_BUFFER_BYTES = 16384

// A healthy channel never reports content it cannot deliver; a run of consecutive torn wakeups
// means the upstream is broken — end the stream honestly rather than pin a core (600%-CPU incident).
private const val MAX_SPURIOUS_WAKEUPS = 1024

// UTF-8 codepoints are at most 4 bytes; carry never needs more than that across a chunk edge.
private const val UTF8_MAX_BYTES = 4

// the chunk/line/skip walk is the literal port; malformed frames must never crash the stream.
// onRawText (opt-in, null for every hot-path caller) exposes the FULL decoded body text as it
// arrives — not just `data:`-prefixed lines — so a zero-event terminal can classify a non-SSE
// dead-head body (HTML/JSON login page). Null-callback matches the `perf: TurnPerf? = null` idiom:
// when null the added cost is one null check per chunk, preserving the no-per-chunk-alloc invariant.
public fun sseJsonEvents(
    channel: ByteReadChannel,
    onBytes: (Int) -> Unit = {},
    onMalformed: (String) -> Unit = {},
    onRawText: ((CharSequence) -> Unit)? = null,
): Flow<JsonObject> = flow {
    val scratch = DecodeScratch()
    val lineBuffer = StringBuilder(READ_BUFFER_BYTES)
    while (true) {
        val n = scratch.readChunk(channel)
        if (n == -1) break
        onBytes(n)
        val before = lineBuffer.length
        scratch.decodeInto(n, lineBuffer)
        if (onRawText != null && lineBuffer.length > before) {
            onRawText(lineBuffer.subSequence(before, lineBuffer.length))
        }
        emitCompleteLines(lineBuffer, onMalformed)
    }
}

/**
 * Reused per-stream decode state: the read buffer, the UTF-8 streaming decoder, its input/output
 * buffers, and the undecoded carry tail (CharsetDecoder does NOT buffer partial codepoints across
 * decode() calls the way Node's streaming TextDecoder does). One allocation per stream, zero per
 * chunk — input capacity is bytes(READ_BUFFER_BYTES) + carry(UTF8_MAX_BYTES), so carry + a full
 * read always fits and no overflow branch is needed.
 */
private class DecodeScratch {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val bytes = ByteArray(READ_BUFFER_BYTES)
    private val inputBuf: ByteBuffer = ByteBuffer.wrap(ByteArray(READ_BUFFER_BYTES + UTF8_MAX_BYTES))
    private val charBuf: CharBuffer = CharBuffer.allocate(READ_BUFFER_BYTES)
    private val carry = ByteArray(UTF8_MAX_BYTES)
    private var carryLen = 0

    /**
     * Read the next chunk; returns byte count (> 0) or -1 at end of stream.
     *
     * On a healthy channel `readAvailable` suspends inside `awaitContent` when the buffer is empty,
     * so the guarded branch below is never reached. It exists for the TORN case — a half-closed /
     * degenerate upstream where `readAvailable` returns 0 WITHOUT suspending. The old
     * `while (readAvailable() == 0)` loop had no suspension or cancellation point there, so a turn
     * whose client already disconnected (turnJob cancelled by the pinger/watchdog) could not exit
     * it: the coroutine hot-spun kqueue syscalls forever, pinning a core per leaked stream — the
     * 600%-CPU / "connection closed mid-response" incident (2026-07-18). The guards make the loop
     * cancellation-cooperative and impossible to hot-spin: honor cancellation, then actually WAIT
     * on `awaitContent` (false == closed → EOF), and bail if the channel keeps claiming content it
     * cannot deliver (a state a healthy channel never produces).
     */
    suspend fun readChunk(channel: ByteReadChannel): Int {
        var spuriousWakeups = 0
        while (true) {
            val n = channel.readAvailable(bytes, 0, bytes.size)
            if (n != 0) return n // > 0 bytes, or -1 at end of stream
            // n == 0 on an open channel: readAvailable did NOT suspend (torn/half-closed peer).
            currentCoroutineContext().ensureActive() // a cancelled turn exits here, never spins
            if (!channel.awaitContent(1)) return -1 // suspends until content or close; false == closed
            if (++spuriousWakeups >= MAX_SPURIOUS_WAKEUPS) return -1 // channel lies — end honestly
        }
    }

    /** Decode carry + the fresh [n] read bytes into [lineBuffer]; retains the new UTF-8 tail. */
    fun decodeInto(n: Int, lineBuffer: StringBuilder) {
        inputBuf.clear()
        if (carryLen > 0) inputBuf.put(carry, 0, carryLen)
        inputBuf.put(bytes, 0, n)
        inputBuf.flip()
        while (true) {
            charBuf.clear()
            val result = decoder.decode(inputBuf, charBuf, false)
            charBuf.flip()
            if (charBuf.hasRemaining()) lineBuffer.append(charBuf)
            if (!result.isOverflow) break
        }
        saveCarry()
    }

    private fun saveCarry() {
        // Remaining is always a partial codepoint (<= 3 bytes) under UTF-8; clamp defensively.
        val keep = inputBuf.remaining().coerceAtMost(carry.size)
        if (keep > 0) {
            inputBuf.position(inputBuf.limit() - keep)
            inputBuf.get(carry, 0, keep)
        }
        carryLen = keep
    }
}

/**
 * Emit every complete `\n`-terminated line in [lineBuffer], compacting the trailing partial
 * in place. No per-line StringBuilder realloc — the same builder is reused for the whole stream.
 */
private suspend fun FlowCollector<JsonObject>.emitCompleteLines(
    lineBuffer: StringBuilder,
    onMalformed: (String) -> Unit,
) {
    var start = 0
    var i = 0
    val end = lineBuffer.length
    while (i < end) {
        if (lineBuffer[i] != '\n') {
            i++
            continue
        }
        var lineEnd = i
        if (lineEnd > start && lineBuffer[lineEnd - 1] == '\r') lineEnd--
        // Blank separator lines between SSE frames never allocate.
        if (lineEnd > start) {
            emitDataLine(lineBuffer, start, lineEnd, onMalformed)
        }
        i++
        start = i
    }
    if (start == 0) return
    if (start >= end) {
        lineBuffer.setLength(0)
    } else {
        // Compact the trailing partial line to the front of the same builder.
        lineBuffer.delete(0, start)
    }
}

private suspend fun FlowCollector<JsonObject>.emitDataLine(
    buf: StringBuilder,
    start: Int,
    end: Int,
    onMalformed: (String) -> Unit,
) {
    if (!buf.matchesAt(start, end, DATA_PREFIX)) return
    // trim ASCII whitespace at both ends (SSE payloads are JSON — no full Unicode trim needed)
    var pStart = start + DATA_PREFIX.length
    var pEnd = end
    while (pStart < pEnd && buf[pStart].isAsciiWs()) pStart++
    while (pEnd > pStart && buf[pEnd - 1].isAsciiWs()) pEnd--
    if (pStart >= pEnd || isDoneSentinel(buf, pStart, pEnd)) return
    val payload = buf.substring(pStart, pEnd)
    runCatchingCancellable { lenient.parseToJsonElement(payload).jsonObject }
        .onFailure { onMalformed(payload) }
        .getOrNull()
        ?.let { emit(it) }
}

/** [literal] present at [start] within [start, end)? Char-wise — no substring allocation. */
private fun StringBuilder.matchesAt(start: Int, end: Int, literal: String): Boolean {
    if (end - start < literal.length) return false
    for (j in literal.indices) {
        if (this[start + j] != literal[j]) return false
    }
    return true
}

private fun isDoneSentinel(buf: StringBuilder, start: Int, end: Int): Boolean =
    end - start == DONE_SENTINEL.length && buf.matchesAt(start, end, DONE_SENTINEL)

private fun Char.isAsciiWs(): Boolean =
    this == ' ' || this == '\t' || this == '\r' || this == '\n'
