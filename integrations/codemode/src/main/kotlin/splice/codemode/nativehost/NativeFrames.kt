package splice.codemode.nativehost

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

// The pinned Codex host protocol's maximum payload; distinct from the legacy JVM worker protocol.
internal const val NATIVE_MAX_FRAME_BYTES: Int = 64 * 1024 * 1024

/** Protocol V1 uses a four-byte little-endian length, followed by one UTF-8 JSON object. */
internal object NativeFrames {
    fun read(input: DataInputStream): JsonObject {
        val length = Integer.reverseBytes(input.readInt())
        if (length !in 1..NATIVE_MAX_FRAME_BYTES) throw IOException("Invalid native host frame length: $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Json.parseToJsonElement(payload.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }

    fun write(output: DataOutputStream, frame: JsonObject) {
        val payload = frame.toString().encodeToByteArray()
        require(payload.size <= NATIVE_MAX_FRAME_BYTES) { "Native host frame exceeds protocol limit" }
        output.writeInt(Integer.reverseBytes(payload.size))
        output.write(payload)
        output.flush()
    }

    fun value(frame: JsonObject): JsonObject {
        val result = frame.getValue("result").jsonObject
        if (JsonScalars.str(result, "status") != "ok") {
            throw IOException(JsonScalars.str(result, "message") ?: "Native host operation failed")
        }
        return result.getValue("value").jsonObject
    }
}
