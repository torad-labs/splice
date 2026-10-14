package splice.head.trace.body

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import splice.core.memory.HeapCategory
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.util.Cancellables
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

private const val LITERAL_DECODER_BYTES = 32L shl 10
private const val LITERAL_HEX_DIGITS = 4
private const val LITERAL_HEX_BASE = 16
private const val LITERAL_BUFFER_CHARS = 8_192
private const val LITERAL_UNSIGNED_BYTE = 0xff

internal fun interface TraceLiteralChunk {
    fun read(part: JsonElement): ByteArray
}

/** Reads the stored JSON string as its exact content, carrying UTF-8 and escape state across chunk boundaries. */
internal class TraceLiteralReader(
    parts: JsonArray,
    chunk: TraceLiteralChunk,
    heap: HeapReservations,
) : Reader() {
    private val lease = HeapOwners.charge(
        this,
        heap,
        TracePackFormat.V2.maxStored.toLong() + CHUNK_MAX * 2L + LITERAL_DECODER_BYTES,
        HeapCategory.DECODED_BODY,
    )
    private val input = InputStreamReader(LiteralBytes(parts, chunk), Charsets.UTF_8)
    private val rawBuffer = CharArray(LITERAL_BUFFER_CHARS)
    private var rawAt = 0
    private var rawCount = 0
    private var started = false
    private var ended = false
    private var closed = false

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        check(!closed)
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        if (!started) start()
        var count = 0
        while (count < length && !ended) {
            val next = character()
            if (next != null) buffer[offset + count++] = next
        }
        return if (count == 0) -1 else count
    }

    private fun raw(): Int {
        if (rawCount < 0) return -1
        if (rawAt == rawCount) {
            rawCount = input.read(rawBuffer)
            rawAt = 0
            if (rawCount < 0) return -1
        }
        return rawBuffer[rawAt++].code
    }

    private fun start() {
        requireLiteral(nonSpace() == '"'.code)
        started = true
    }

    private fun character(): Char? = when (val next = raw()) {
        '"'.code -> {
            requireLiteral(nonSpace() == -1)
            ended = true
            null
        }
        '\\'.code -> escaped()
        else -> {
            requireLiteral(next >= ' '.code)
            next.toChar()
        }
    }

    private fun escaped(): Char = when (val next = raw()) {
        '"'.code, '\\'.code, '/'.code -> next.toChar()
        'b'.code -> '\b'
        'f'.code -> '\u000c'
        'n'.code -> '\n'
        'r'.code -> '\r'
        't'.code -> '\t'
        'u'.code -> unicode()
        else -> throw IOException("invalid trace body chunk literal")
    }

    private fun unicode(): Char {
        var code = 0
        repeat(LITERAL_HEX_DIGITS) {
            val char = raw().takeIf { it >= 0 }?.toChar()
            requireLiteral(char != null && char in "0123456789abcdefABCDEF")
            code = code * LITERAL_HEX_BASE + checkNotNull(char).digitToInt(LITERAL_HEX_BASE)
        }
        return code.toChar()
    }

    private fun nonSpace(): Int {
        var next = raw()
        while (next >= 0 && next.toChar() in " \t\r\n") next = raw()
        return next
    }

    private fun requireLiteral(valid: Boolean) {
        if (!valid) throw IOException("invalid trace body chunk literal")
    }

    override fun close() {
        if (closed) return
        closed = true
        Cancellables.withCleanup({ lease.close() }) { input.close() }
    }
}

/** The literal lease covers the previous chunk, its replacement and the UTF-8 decoder's private buffer. */
private class LiteralBytes(private val parts: JsonArray, private val chunk: TraceLiteralChunk) : InputStream() {
    private var part = 0
    private var bytes = byteArrayOf()
    private var at = 0

    override fun read(): Int = if (availableChunk()) bytes[at++].toInt() and LITERAL_UNSIGNED_BYTE else -1

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        if (!availableChunk()) return -1
        val count = minOf(length, bytes.size - at)
        bytes.copyInto(buffer, offset, at, at + count)
        at += count
        return count
    }

    private fun availableChunk(): Boolean {
        while (at == bytes.size && part < parts.size) {
            bytes = chunk.read(parts[part++])
            at = 0
        }
        return at < bytes.size
    }

    override fun close() {
        bytes = byteArrayOf()
        part = parts.size
    }
}
