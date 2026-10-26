// PORT-OF: codex-rs/code-mode/src/grpc_session/conversion.rs at rust-v0.164.0.
package splice.codemode.nativehost.grpc

import com.google.protobuf.ByteString
import com.google.protobuf.DynamicMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import splice.codemode.nativehost.NATIVE_MAX_FRAME_BYTES
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

/** Preserves optional JSON bytes separately from explicit JSON null. */
internal object NativeGrpcRequests {
    fun open(request: JsonObject): DynamicMessage {
        val limits = request["cellExecutionLimits"] as? JsonObject
        val message = limits?.let {
            NativeGrpcWire.message(
                "SessionCellExecutionLimits",
                mapOf(
                    "max_yield_time_ms" to unsigned(it, "maxYieldTimeMs"),
                    "max_heap_size_bytes" to unsigned(it, "maxHeapSizeBytes"),
                ),
            )
        }
        return NativeGrpcWire.message("OpenSessionRequest", mapOf("cell_execution_limits" to message))
    }

    fun execute(session: String, execution: String, request: JsonObject): DynamicMessage = NativeGrpcWire.message(
        "ExecuteRequest",
        mapOf(
            "session_id" to session,
            "execution_id" to execution,
            "tool_call_id" to requireNotNull(JsonScalars.str(request, "tool_call_id")),
            "source" to requireNotNull(JsonScalars.str(request, "source")),
            "enabled_tools" to request.getValue("enabled_tools").jsonArray.map { tool(it.jsonObject) },
            NATIVE_GRPC_YIELD_FIELD to unsigned(request, NATIVE_GRPC_YIELD_FIELD),
            "max_output_tokens" to unsigned(request, "max_output_tokens"),
        ),
    )

    fun wait(session: String, wait: String, request: JsonObject): DynamicMessage = NativeGrpcWire.message(
        "WaitRequest",
        mapOf(
            "session_id" to session,
            "cell_id" to requireNotNull(JsonScalars.str(request, "cell_id")),
            "wait_id" to wait,
            NATIVE_GRPC_YIELD_FIELD to requireNotNull(unsigned(request, NATIVE_GRPC_YIELD_FIELD)),
        ),
    )

    fun toolResult(session: String, invocation: String, result: JsonObject): DynamicMessage {
        val failed = JsonScalars.str(result, "status") == "error"
        val payload = if (failed) {
            NativeGrpcWire.message("ToolCallFailed", mapOf("message" to JsonScalars.str(result, "message").orEmpty()))
        } else {
            val value = result.getValue("value").jsonObject.getValue("result")
            NativeGrpcWire.message("ToolCallSucceeded", mapOf("output_json" to bytes(value)))
        }
        return boundedResult(
            NativeGrpcWire.message(
                "CompleteToolCallRequest",
                mapOf(
                    "session_id" to session,
                    "invocation_id" to invocation,
                    (if (failed) "failed" else "succeeded") to payload,
                ),
            ),
        )
    }

    // PORT-OF: grpc_session/completion.rs checks the entire protobuf request after JSON encoding.
    private fun boundedResult(request: DynamicMessage): DynamicMessage {
        val encoded = request.serializedSize
        if (encoded <= NATIVE_MAX_FRAME_BYTES) return request
        val message = "code-mode tool result of $encoded encoded bytes exceeds the gRPC message limit of " +
            "$NATIVE_MAX_FRAME_BYTES bytes"
        val failed = NativeGrpcWire.message("ToolCallFailed", mapOf("message" to message))
        return request.toBuilder().setField(request.descriptorForType.findFieldByName("failed"), failed).build()
    }

    private fun tool(tool: JsonObject): DynamicMessage {
        val name = tool.getValue("tool_name").jsonObject
        val input = tool["input_schema"]
        val output = tool["output_schema"]
        return NativeGrpcWire.message(
            "ToolDefinition",
            mapOf(
                "name" to requireNotNull(JsonScalars.str(tool, "name")),
                "tool_name" to NativeGrpcWire.message(
                    "ToolName",
                    mapOf(
                        "name" to requireNotNull(JsonScalars.str(name, "name")),
                        "namespace" to JsonScalars.str(name, "namespace"),
                    ),
                ),
                "description" to JsonScalars.str(tool, "description").orEmpty(),
                "kind" to when (JsonScalars.str(tool, "kind")) {
                    "function" -> "TOOL_KIND_FUNCTION"
                    "freeform" -> "TOOL_KIND_FREEFORM"
                    else -> error("Invalid CodeMode tool kind")
                },
                "input_schema_json" to input?.let(::bytes),
                "output_schema_json" to output?.let(::bytes),
            ),
        )
    }

    private fun bytes(value: JsonElement): ByteString = ByteString.copyFromUtf8(JsonWire.string(value))

    private fun unsigned(request: JsonObject, field: String): Long? {
        val value = request[field].takeUnless { it == null || it == JsonNull } ?: return null
        require(value is JsonPrimitive && !value.isString) { "Invalid CodeMode uint64 $field" }
        return requireNotNull(
            JsonScalars.str(request, field)?.toULongOrNull(),
        ) { "Invalid CodeMode uint64 $field" }.toLong()
    }
}

// The same optional uint64 protocol field is used by Execute and the mandatory Wait budget.
private const val NATIVE_GRPC_YIELD_FIELD = "yield_time_ms"
