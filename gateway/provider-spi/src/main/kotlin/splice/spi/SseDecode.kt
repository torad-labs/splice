// NEW: per-stream UTF-8 decode scratch and WHATWG event assembler.
// Split from SseReader.kt so the line walk is not billed for the
// decode/assemble state machines (concentration HIGH, 2026-08-19).
package splice.spi

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

// FILE SCOPE ON PURPOSE: one configured parser for every stream in the process. As a member it
// would be rebuilt per SseReader construction, and the head constructs one per upstream round.
private val lenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal const val SSE_READ_BUFFER_BYTES = 16384

// UTF-8 codepoints are at most 4 bytes; carry never needs more than that across a chunk edge.
private const val UTF8_MAX_BYTES = 4

private const val DONE_SENTINEL = "[DONE]"

/**
 * Reused per-stream decode state: the read buffer, the UTF-8 streaming decoder, its input/output
 * buffers, and the undecoded carry tail (CharsetDecoder does NOT buffer partial codepoints across
 * decode() calls the way Node's streaming TextDecoder does). One allocation per stream, zero per
 * chunk — input capacity is bytes(SSE_READ_BUFFER_BYTES) + carry(UTF8_MAX_BYTES), so carry + a full
 * read always fits and no overflow branch is needed.
 */
internal class DecodeScratch {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val bytes = ByteArray(SSE_READ_BUFFER_BYTES)
    private val inputBuf: ByteBuffer = ByteBuffer.wrap(ByteArray(SSE_READ_BUFFER_BYTES + UTF8_MAX_BYTES))
    private val charBuf: CharBuffer = CharBuffer.allocate(SSE_READ_BUFFER_BYTES)
    private val carry = ByteArray(UTF8_MAX_BYTES)
    private var carryLen = 0

    /**
     * Read the next chunk; returns byte count (> 0) or -1 at end of stream.
     *
     * The walk itself, and the reasoning behind its two guards (the 600%-CPU /
     * "connection closed mid-response" incident of 2026-07-18), now live in
     * [ChannelReads.readAvailableOrEof] — this was the only copy that carried the fix, and review
     * 2026-08-28 found the other two still on the pre-fix shape. One home, three callers.
     */
    suspend fun readChunk(channel: ByteReadChannel): Int = ChannelReads.readAvailableOrEof(channel, bytes)

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
 * Reused per-stream event-assembly state that must persist ACROSS [SseReader.emitCompleteLines] calls
 * (i.e. across chunk boundaries), mirroring how [DecodeScratch] carries decode state across chunks. One
 * allocation per stream. Implements the WHATWG blank-line-dispatch model: `data:` field values
 * accumulate into [dataBuffer] and are only turned into an event on a blank line ([dispatch]); a
 * pending buffer at EOF is discarded (the outer loop never flushes).
 */
internal class SseEventAssembler(
    private val onMalformed: MalformedLine,
    private val maxEventChars: Int,
) {
    // Joined `data:` field values for the event not yet dispatched. A single `data:` line with an
    // EMPTY value is a no-op append (spec-literal "append then strip one trailing LF" is skipped):
    // since every real payload is JSON-parsed and JSON treats leading/trailing/inner whitespace and
    // newlines as insignificant, both bookkeeping styles yield identical parse results, and an
    // empty-only buffer fails `parseToJsonElement("")` identically either way (no emitted event).
    val dataBuffer = StringBuilder()

    fun append(buf: StringBuilder, start: Int, end: Int) {
        val separator = if (dataBuffer.isEmpty()) 0 else 1
        if (dataBuffer.length + separator + (end - start) > maxEventChars) {
            throw SseFrameTooLargeException("SSE event", maxEventChars)
        }
        if (separator == 1) dataBuffer.append('\n')
        dataBuffer.append(buf, start, end)
    }

    // True when the most recently scanned char was a bare `\r` whose following byte hadn't arrived
    // yet — the CRLF-vs-lone-CR chunk-boundary case (a `\n` opening the next chunk completes a CRLF).
    var pendingCR = false

    /** Dispatch the pending event (WHATWG): empty buffer aborts; [DONE_SENTINEL] and malformed JSON never emit. */
    suspend fun dispatch(collector: FlowCollector<JsonObject>) {
        if (dataBuffer.isEmpty()) return
        val payload = dataBuffer.toString()
        dataBuffer.setLength(0)
        if (payload == DONE_SENTINEL) return
        Cancellables.runCatchingCancellable { lenient.parseToJsonElement(payload).jsonObject }
            .onFailure { onMalformed(payload) }
            .getOrNull()
            ?.let { collector.emit(it) }
    }
}
