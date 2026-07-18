// PORT-OF: server/src/codex/stream.mjs sseEvents @ 4ca99f7 — invariants: multi-byte-safe UTF-8
// across chunk boundaries (streaming decoder, never split a codepoint); partial last line
// carries to the next chunk; only `data: `-prefixed lines yield; empty payloads and [DONE]
// skipped; malformed JSON frames skipped (never crash the stream); onBytes fires ON RAW READ
// (the watchdog touch — never after downstream write; a slow client must not fake idleness).
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
private const val READ_BUFFER_BYTES = 16384

// the chunk/line/skip walk is the literal port; malformed frames must never crash the stream
public fun sseJsonEvents(channel: ByteReadChannel, onBytes: () -> Unit = {}): Flow<JsonObject> = flow {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val bytes = ByteArray(READ_BUFFER_BYTES)
    var lineBuffer = StringBuilder()
    // CharsetDecoder does NOT buffer partial codepoints across decode() calls (Node's
    // streaming TextDecoder does) — undecoded tail bytes must be carried explicitly.
    var carry = ByteArray(0)

    while (true) {
        val n = readChunk(channel, bytes)
        if (n == -1) break
        onBytes()
        carry = decodeChunk(decoder, carry, bytes, n, lineBuffer)
        lineBuffer = emitCompleteLines(lineBuffer)
    }
}

/** Read the next chunk, skipping empty (n == 0) reads; returns byte count or -1 at end of stream. */
private suspend fun readChunk(channel: ByteReadChannel, bytes: ByteArray): Int {
    while (true) {
        val n = channel.readAvailable(bytes, 0, bytes.size)
        if (n != 0) return n
    }
}

/** Decode carry + the fresh [n] bytes into [lineBuffer]; returns the undecoded tail to carry forward. */
private fun decodeChunk(
    decoder: CharsetDecoder,
    carry: ByteArray,
    bytes: ByteArray,
    n: Int,
    lineBuffer: StringBuilder,
): ByteArray {
    val input = ByteBuffer.allocate(carry.size + n)
    input.put(carry)
    input.put(bytes, 0, n)
    input.flip()
    val charBuffer = CharBuffer.allocate(READ_BUFFER_BYTES)
    while (true) {
        charBuffer.clear()
        val result = decoder.decode(input, charBuffer, false)
        charBuffer.flip()
        lineBuffer.append(charBuffer)
        if (!result.isOverflow) break
    }
    return ByteArray(input.remaining()).also { input.get(it) }
}

/** Emit every complete `\n`-terminated line in [lineBuffer]; returns the trailing partial line. */
private suspend fun FlowCollector<JsonObject>.emitCompleteLines(lineBuffer: StringBuilder): StringBuilder {
    var buffer = lineBuffer
    var newlineAt = buffer.indexOf("\n")
    while (newlineAt >= 0) {
        val line = buffer.substring(0, newlineAt).removeSuffix("\r")
        buffer = StringBuilder(buffer.substring(newlineAt + 1))
        newlineAt = buffer.indexOf("\n")
        emitDataLine(line)
    }
    return buffer
}

private suspend fun FlowCollector<JsonObject>.emitDataLine(line: String) {
    if (!line.startsWith(DATA_PREFIX)) return
    val payload = line.substring(DATA_PREFIX.length).trim()
    if (payload.isEmpty() || payload == "[DONE]") return
    // skip malformed frame — the terminal sweep handles truncation honestly
    runCatchingCancellable { lenient.parseToJsonElement(payload).jsonObject }
        .getOrNull()
        ?.let { emit(it) }
}
