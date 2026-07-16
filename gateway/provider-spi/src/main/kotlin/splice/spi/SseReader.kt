// PORT-OF: server/src/codex/stream.mjs sseEvents @ 4ca99f7 — invariants: multi-byte-safe UTF-8
// across chunk boundaries (streaming decoder, never split a codepoint); partial last line
// carries to the next chunk; only `data: `-prefixed lines yield; empty payloads and [DONE]
// skipped; malformed JSON frames skipped (never crash the stream); onBytes fires ON RAW READ
// (the watchdog touch — never after downstream write; a slow client must not fake idleness).
package splice.spi

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException

private val lenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
private const val DATA_PREFIX = "data: "
private const val READ_BUFFER_BYTES = 16384

@Suppress(
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "TooGenericExceptionCaught",
    "InstanceOfCheckForException",
) // the chunk/line/skip walk is the literal port; malformed frames must never crash the stream
public fun sseJsonEvents(channel: ByteReadChannel, onBytes: () -> Unit = {}): Flow<JsonObject> = flow {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val bytes = ByteArray(READ_BUFFER_BYTES)
    val charBuffer = CharBuffer.allocate(READ_BUFFER_BYTES)
    var lineBuffer = StringBuilder()
    // CharsetDecoder does NOT buffer partial codepoints across decode() calls (Node's
    // streaming TextDecoder does) — undecoded tail bytes must be carried explicitly.
    var carry = ByteArray(0)

    while (true) {
        val n = channel.readAvailable(bytes, 0, bytes.size)
        if (n == -1) break
        if (n == 0) continue
        onBytes()

        val input = ByteBuffer.allocate(carry.size + n)
        input.put(carry)
        input.put(bytes, 0, n)
        input.flip()
        while (true) {
            charBuffer.clear()
            val result = decoder.decode(input, charBuffer, false)
            charBuffer.flip()
            lineBuffer.append(charBuffer)
            if (!result.isOverflow) break
        }
        carry = ByteArray(input.remaining()).also { input.get(it) }

        var newlineAt = lineBuffer.indexOf("\n")
        while (newlineAt >= 0) {
            val line = lineBuffer.substring(0, newlineAt).removeSuffix("\r")
            lineBuffer = StringBuilder(lineBuffer.substring(newlineAt + 1))
            newlineAt = lineBuffer.indexOf("\n")

            if (!line.startsWith(DATA_PREFIX)) continue
            val payload = line.substring(DATA_PREFIX.length).trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val parsed = try {
                lenient.parseToJsonElement(payload).jsonObject
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                continue // skip malformed frame — the terminal sweep handles truncation honestly
            }
            emit(parsed)
        }
    }
}
