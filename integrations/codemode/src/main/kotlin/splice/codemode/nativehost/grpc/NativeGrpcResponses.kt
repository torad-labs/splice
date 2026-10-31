// PORT-OF: codex-rs/code-mode/src/grpc_session/conversion.rs at rust-v0.164.0.
package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.Metadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object NativeGrpcResponses {
    fun outcome(message: DynamicMessage, buffered: ULong = 0u): JsonObject {
        val kind = when {
            NativeGrpcFields.optional(message, "yielded") != null -> "Yielded"
            NativeGrpcFields.optional(message, "terminated") != null -> "Terminated"
            NativeGrpcFields.optional(message, "completed") != null -> "Result"
            else -> throw NativeGrpcProtocolException("code-mode execution omitted its outcome")
        }
        return buildJsonObject {
            put(
                kind,
                buildJsonObject {
                    put("cell_id", NativeGrpcFields.identifier(message, "cell_id"))
                    put(
                        "content_items",
                        buildJsonArray {
                            NativeGrpcFields.messages(
                                message,
                                "content_items",
                            ).forEach { add(NativeGrpcContent.decode(it)) }
                        },
                    )
                    put(
                        "code_mode_host_duration_ns",
                        unsigned(NativeGrpcFields.unsigned(message, "code_mode_host_duration_ns")),
                    )
                    if (kind == "Result") {
                        val completed = NativeGrpcFields.message(message, "completed")
                        val error = NativeGrpcWire.value(completed, "error_text") as? String
                        put("error_text", error?.let(::JsonPrimitive) ?: JsonNull)
                        put("result_buffered_for_ns", unsigned(buffered))
                    }
                },
            )
        }
    }

    fun wait(reply: NativeGrpcReply): JsonObject {
        val live = NativeGrpcFields.optional(reply.message, "live_cell")
        val message = live ?: NativeGrpcFields.optional(reply.message, "missing_cell")
            ?: throw NativeGrpcProtocolException("code-mode wait omitted its outcome")
        val buffered = if (live != null) buffered(reply.headers) else 0u
        return buildJsonObject {
            put("type", "wait/completed")
            put(
                "outcome",
                buildJsonObject { put(if (live != null) "LiveCell" else "MissingCell", outcome(message, buffered)) },
            )
        }
    }

    private fun buffered(headers: Metadata): ULong = headers.get(
        Metadata.Key.of("codex-code-mode-result-buffered-for-ns", Metadata.ASCII_STRING_MARSHALLER),
    )?.toULongOrNull() ?: 0u

    private fun unsigned(number: ULong): kotlinx.serialization.json.JsonElement = Json.parseToJsonElement(
        number.toString(),
    )
}
