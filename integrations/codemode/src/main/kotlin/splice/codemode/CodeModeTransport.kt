// NEW: framed worker I/O distinguishes transport cuts from complete malformed protocol frames.
package splice.codemode

import kotlinx.serialization.json.JsonObject
import splice.upstream.codemode.CodeModeLimits
import splice.upstream.codemode.CodeModeProtocol
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

internal object CodeModeTransport {
    fun write(output: DataOutputStream, frame: JsonObject) {
        val bytes = CodeModeProtocol.encodeFrame(frame)
        require(bytes.size <= CodeModeLimits.MAX_FRAME_BYTES) {
            "Code-mode frame exceeds ${CodeModeLimits.MAX_FRAME_BYTES} bytes"
        }
        try {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        } catch (error: IOException) {
            throw CodeModeWorkerLostException(error)
        }
    }

    fun read(input: DataInputStream): JsonObject {
        val bytes = ByteArray(readFrameSize(input))
        try {
            input.readFully(bytes)
        } catch (error: IOException) {
            throw CodeModeWorkerLostException(error)
        }
        return parseFrame(bytes)
    }

    private fun readFrameSize(input: DataInputStream): Int {
        val size = try {
            input.readInt()
        } catch (error: IOException) {
            throw CodeModeWorkerLostException(error)
        }
        if (size !in 1..CodeModeLimits.MAX_FRAME_BYTES) {
            throw IOException("Code-mode worker sent an invalid frame length")
        }
        return size
    }

    private fun parseFrame(bytes: ByteArray): JsonObject {
        val element = try {
            CodeModeJson.codec.parseToJsonElement(bytes.decodeToString())
        } catch (error: IllegalArgumentException) {
            throw IOException("Code-mode worker sent invalid JSON", error)
        }
        return element as? JsonObject ?: throw IOException("Code-mode worker sent a non-object frame")
    }
}
