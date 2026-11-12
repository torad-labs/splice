// PORT-OF: codex-rs/code-mode/src/grpc_session/conversion.rs at rust-v0.164.0.
package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object NativeGrpcContent {
    fun decode(item: DynamicMessage): JsonObject = when {
        NativeGrpcFields.optional(item, "text") != null -> buildJsonObject {
            put("type", "input_text")
            put("text", NativeGrpcFields.text(NativeGrpcFields.message(item, "text"), "text"))
        }
        NativeGrpcFields.optional(item, "image") != null -> image(NativeGrpcFields.message(item, "image"))
        NativeGrpcFields.optional(item, "audio") != null -> buildJsonObject {
            put("type", "input_audio")
            put("audio_url", NativeGrpcFields.text(NativeGrpcFields.message(item, "audio"), "audio_url"))
        }
        else -> throw NativeGrpcProtocolException("code-mode execution returned an empty content item")
    }

    private fun image(image: DynamicMessage): JsonObject = buildJsonObject {
        put("type", "input_image")
        put("image_url", NativeGrpcFields.text(image, "image_url"))
        if (NativeGrpcWire.value(image, "detail") != null) {
            val offered = NativeGrpcFields.enumeration(image, "detail")
            val detail = when (offered.name) {
                "IMAGE_DETAIL_AUTO" -> "auto"
                "IMAGE_DETAIL_LOW" -> "low"
                "IMAGE_DETAIL_HIGH" -> "high"
                "IMAGE_DETAIL_ORIGINAL" -> "original"
                else -> throw NativeGrpcProtocolException("code-mode image has invalid detail value ${offered.number}")
            }
            put("detail", detail)
        }
    }
}
