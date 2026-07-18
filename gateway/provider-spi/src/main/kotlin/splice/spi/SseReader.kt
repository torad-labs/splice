// PORT-OF: server/src/codex/stream.mjs sseEvents @ 4ca99f7 — invariants: multi-byte-safe UTF-8
// across chunk boundaries (streaming decoder, never split a codepoint); partial last line
// carries to the next chunk; only `data: `-prefixed lines yield; empty payloads and [DONE]
// skipped; malformed JSON frames skipped (never crash the stream); onBytes fires ON RAW READ
// with the chunk size (the watchdog touch + byte telemetry — never after downstream write; a
// slow client must not fake idleness). Hot-path shape: one reused decode scratch per stream
// (no per-chunk buffer allocs) and index-scanned lines (no per-line StringBuilder churn).
package splice.spi

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
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

private const val DATA_PREFIX = "data: "
private const val DONE_SENTINEL = "[DONE]"
private const val READ_BUFFER_BYTES = 16384

// UTF-8 codepoints are at most 4 bytes; carry never needs more than that across a chunk edge.
private const val UTF8_MAX_BYTES = 4

// the chunk/line/skip walk is the literal port; malformed frames must never crash the stream
public fun sseJsonEvents(channel: ByteReadChannel, onBytes: (Int) -> Unit = {}): Flow<JsonObject> = flow {
    val scratch = DecodeScratch()
    val lineBuffer = StringBuilder(READ_BUFFER_BYTES)
    while (true) {
        val n = scratch.readChunk(channel)
        if (n == -1) break
        onBytes(n)
        scratch.decodeInto(n, lineBuffer)
        emitCompleteLines(lineBuffer)
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

    /** Read the next chunk, skipping empty (n == 0) reads; returns byte count or -1 at end of stream. */
    suspend fun readChunk(channel: ByteReadChannel): Int {
        while (true) {
            val n = channel.readAvailable(bytes, 0, bytes.size)
            if (n != 0) return n
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
private suspend fun FlowCollector<JsonObject>.emitCompleteLines(lineBuffer: StringBuilder) {
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
            emitDataLine(lineBuffer, start, lineEnd)
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

private suspend fun FlowCollector<JsonObject>.emitDataLine(buf: StringBuilder, start: Int, end: Int) {
    if (!buf.matchesAt(start, end, DATA_PREFIX)) return
    // trim ASCII whitespace at both ends (SSE payloads are JSON — no full Unicode trim needed)
    var pStart = start + DATA_PREFIX.length
    var pEnd = end
    while (pStart < pEnd && buf[pStart].isAsciiWs()) pStart++
    while (pEnd > pStart && buf[pEnd - 1].isAsciiWs()) pEnd--
    if (pStart >= pEnd || isDoneSentinel(buf, pStart, pEnd)) return
    val payload = buf.substring(pStart, pEnd)
    // skip malformed frame — the terminal sweep handles truncation honestly
    runCatchingCancellable { lenient.parseToJsonElement(payload).jsonObject }
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
