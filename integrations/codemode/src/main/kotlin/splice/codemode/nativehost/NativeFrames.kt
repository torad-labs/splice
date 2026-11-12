package splice.codemode.nativehost

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.upstream.failure.CodeModeToolException

// The pinned Codex host protocol's maximum payload; distinct from the legacy JVM worker protocol.
internal const val NATIVE_MAX_FRAME_BYTES: Int = 64 * 1024 * 1024

/** Internal request/event envelopes; the native process connection uses the current gRPC schema. */
internal object NativeFrames {
    fun value(frame: JsonObject): JsonObject {
        val result = frame.getValue("result").jsonObject
        if (JsonScalars.str(result, "status") != "ok") {
            throw CodeModeToolException(
                JsonScalars.str(result, "message") ?: "Native host operation failed",
                preserveCell = JsonScalars.str(result, "preserveCell") == "true",
            )
        }
        return result.getValue("value").jsonObject
    }
}
