package splice.app.mods.run

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.launch.ModsOff
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream

private const val MOD_LINE_BYTES = 256 * 1024 // why: bound memory for a broken or flooding peer

internal fun interface ModEventProjection {
    operator fun invoke(): ModEvent?

    val unresolved: Boolean get() = false
}

internal fun interface ModEventAudience {
    fun options(): ModsOff?
}

@Serializable
internal data class ModEvent(
    val name: String,
    val sessionId: String?,
    val payload: JsonObject,
    @Transient private val projection: ModEventProjection? = null,
    @Transient val audience: ModEventAudience? = null,
) {
    val unresolved: Boolean get() = projection?.unresolved == true

    /** Only the pipe writer or handover sender projects a pending event. */
    fun sealed(): ModEvent? = if (projection == null) this else projection()

    fun message(): JsonObject = buildJsonObject {
        put("type", "event")
        put("name", name)
        put("session_id", sessionId)
        put("payload", payload)
    }
}

internal object ModLine {
    /** A null decode is counted by the transport, just like a non-object or oversized line. */
    fun decode(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    fun write(stream: OutputStream, value: JsonObject): Boolean {
        val bytes = value.toString().encodeToByteArray()
        if (bytes.size > MOD_LINE_BYTES) return false
        stream.write(bytes)
        stream.write('\n'.code)
        stream.flush()
        return true
    }
}

/** Discard an oversized line through its delimiter, without retaining the discarded bytes. */
internal class ModLineReader(stream: InputStream) {
    private val input = BufferedInputStream(stream)
    private val bytes = ByteArray(MOD_LINE_BYTES)

    inline fun drain(line: (String?) -> Unit) {
        var used = 0
        var byte = input.read()
        while (byte != -1) {
            if (byte == '\n'.code) {
                line(text(used))
                used = 0
            } else if (used <= bytes.size) {
                if (used < bytes.size) bytes[used] = byte.toByte()
                used++
            }
            byte = input.read()
        }
        if (used != 0) line(text(used))
    }

    private fun text(size: Int): String? =
        if (size > bytes.size) null else bytes.decodeToString(endIndex = size)
}
