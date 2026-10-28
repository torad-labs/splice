// PORT-OF: codex-rs/code-mode/src/grpc_session/conversion.rs at rust-v0.164.0.
package splice.codemode.nativehost.grpc

import com.google.protobuf.ByteString
import com.google.protobuf.DynamicMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.codemode.nativehost.grpc.state.NativeGrpcInputFailure
import splice.upstream.codemode.json.CodeModeJson
import splice.upstream.codemode.json.CodeModeJsonException
import java.io.IOException

internal object NativeGrpcInvocations {
    fun tool(call: DynamicMessage): JsonObject = buildJsonObject {
        put("type", "tool/invoke")
        put(
            "invocation",
            buildJsonObject {
                put("cell_id", NativeGrpcFields.identifier(call, "cell_id"))
                put("runtime_tool_call_id", NativeGrpcFields.text(call, "runtime_tool_call_id"))
                val name = NativeGrpcFields.message(call, "tool_name")
                put(
                    "tool_name",
                    buildJsonObject {
                        put("name", NativeGrpcFields.text(name, "name"))
                        val namespace = NativeGrpcWire.value(name, "namespace") as? String
                        put("namespace", namespace?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
                put(
                    "tool_kind",
                    when (NativeGrpcFields.enumeration(call, "tool_kind").name) {
                        "TOOL_KIND_FUNCTION" -> "function"
                        "TOOL_KIND_FREEFORM" -> "freeform"
                        else -> throw IOException("CodeMode tool invocation has invalid kind")
                    },
                )
                NativeGrpcFields.bytes(call, "input_json")?.let { put("input", input(it)) }
            },
        )
    }

    fun notification(message: DynamicMessage): JsonObject = buildJsonObject {
        put("type", "notification/send")
        put("cellId", NativeGrpcFields.identifier(message, "cell_id"))
        put("callId", NativeGrpcFields.identifier(message, "call_id"))
        put("text", NativeGrpcFields.text(message, "text"))
    }

    private fun input(bytes: ByteString): JsonElement {
        return try {
            CodeModeJson.parse(bytes.toByteArray())
        } catch (error: CodeModeJsonException) {
            throw NativeGrpcInputFailure("code-mode tool invocation contains invalid JSON: ${error.diagnostic}", error)
        }
    }
}
