package splice.app.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonWire
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The diagnostic uses the reference host framing, keeping scripts out of command arguments. */
internal class CodeModeParityFrames {
    private val input = BufferedInputStream(System.`in`)
    private val output = BufferedOutputStream(System.out)

    fun read(): JsonObject? {
        val prefix = input.readNBytes(Int.SIZE_BYTES)
        if (prefix.isEmpty()) return null
        if (prefix.size != Int.SIZE_BYTES) throw EOFException("Incomplete parity frame")
        val size = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).int
        require(size in 1..MAX_PARITY_FRAME_BYTES) { "Invalid parity frame size" }
        val payload = input.readNBytes(size)
        if (payload.size != size) throw EOFException("Incomplete parity payload")
        return Json.parseToJsonElement(payload.decodeToString()).jsonObject
    }

    @Synchronized
    fun send(frame: JsonObject) {
        val payload = JsonWire.string(frame).encodeToByteArray()
        require(payload.size <= MAX_PARITY_FRAME_BYTES) { "Parity response exceeds frame limit" }
        output.write(ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array())
        output.write(payload)
        output.flush()
    }
}

// The pinned native V1 transport bounds each serialized frame to 64 MiB.
private const val MAX_PARITY_FRAME_BYTES = 64 * 1024 * 1024
