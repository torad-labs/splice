package splice.codemode.nativehost

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeResultDisposition
import splice.upstream.codemode.CodeModeToolOutputs
import splice.upstream.codemode.NativeCodeModeNotificationDelivery
import splice.upstream.codemode.NativeCodeModeOptions
import java.math.BigInteger

private const val NATIVE_METHOD: String = "method"
private const val NATIVE_SESSION_ID: String = "sessionId"

// why: tool and notification delegate responses share the same success/error envelope field.
private const val NATIVE_RESULT_STATUS = "status"

internal object NativeRequests {
    fun control(type: String, id: Long): JsonObject = buildJsonObject {
        put("type", type)
        put("id", id)
    }

    fun session(method: String, sessionId: String): JsonObject = buildJsonObject {
        put(NATIVE_METHOD, method)
        put(NATIVE_SESSION_ID, sessionId)
    }

    fun open(sessionId: String, heapBytes: Long, maxYieldTimeMs: Long? = null): JsonObject = buildJsonObject {
        put(NATIVE_METHOD, "session/open")
        put(NATIVE_SESSION_ID, sessionId)
        put(
            "cellExecutionLimits",
            buildJsonObject {
                put("maxHeapSizeBytes", heapBytes)
                maxYieldTimeMs?.let { put("maxYieldTimeMs", it) }
            },
        )
    }

    fun execute(
        sessionId: String,
        source: String,
        catalog: List<CodeModeManual.NestedTool>,
        options: NativeCodeModeOptions,
    ) =
        buildJsonObject {
            put(NATIVE_METHOD, "session/execute")
            put(NATIVE_SESSION_ID, sessionId)
            put(
                "request",
                buildJsonObject {
                    put("tool_call_id", options.toolCallId ?: java.util.UUID.randomUUID().toString())
                    put(
                        "enabled_tools",
                        buildJsonArray {
                            catalog.forEach { add(tool(it)) }
                        },
                    )
                    put("source", source)
                    put(
                        "yield_time_ms",
                        JsonPrimitive(options.unsignedYieldTimeMs ?: BigInteger.valueOf(options.yieldTimeMs)),
                    )
                    put(
                        "max_output_tokens",
                        JsonPrimitive(options.unsignedMaxOutputTokens ?: BigInteger.valueOf(options.maxOutputTokens)),
                    )
                },
            )
        }

    private fun tool(definition: CodeModeManual.NestedTool): JsonObject = buildJsonObject {
        put("name", definition.codeModeName)
        put(
            "tool_name",
            buildJsonObject {
                put("name", definition.name)
                put("namespace", definition.metadata.namespace?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
        put("description", definition.description)
        put(
            "kind",
            when (definition.metadata.kind) {
                CodeModeManual.Kind.FUNCTION -> "function"
                CodeModeManual.Kind.FREEFORM -> "freeform"
            },
        )
        definition.inputSchema?.let { put("input_schema", it) }
        definition.outputSchema?.let { put("output_schema", it) }
    }

    fun wait(sessionId: String, cellId: String, yieldTimeMs: Long): JsonObject =
        wait(sessionId, cellId, BigInteger.valueOf(yieldTimeMs))

    fun wait(sessionId: String, cellId: String, yieldTimeMs: BigInteger): JsonObject = buildJsonObject {
        put(NATIVE_METHOD, "session/wait")
        put(NATIVE_SESSION_ID, sessionId)
        put(
            "request",
            buildJsonObject {
                put("cell_id", cellId)
                put("yield_time_ms", JsonPrimitive(yieldTimeMs))
            },
        )
    }

    fun terminate(sessionId: String, cellId: String): JsonObject = buildJsonObject {
        put(NATIVE_METHOD, "session/terminate")
        put(NATIVE_SESSION_ID, sessionId)
        put("cellId", cellId)
    }

    fun delegate(id: JsonElement, result: CodeModeResult): JsonObject = buildJsonObject {
        put("type", "delegate/response")
        put("id", id)
        put(
            "result",
            buildJsonObject {
                if (result.isError && result.disposition == CodeModeResultDisposition.REJECT) {
                    put(NATIVE_RESULT_STATUS, "error")
                    put("message", result.output)
                } else {
                    put(NATIVE_RESULT_STATUS, "ok")
                    put(
                        "value",
                        buildJsonObject {
                            put("type", "tool/result")
                            put("result", CodeModeToolOutputs.value(result))
                        },
                    )
                }
            },
        )
    }

    fun notified(
        id: JsonElement,
        delivered: NativeCodeModeNotificationDelivery = NativeCodeModeNotificationDelivery.Delivered,
    ): JsonObject = buildJsonObject {
        put("type", "delegate/response")
        put("id", id)
        put(
            "result",
            buildJsonObject {
                when (delivered) {
                    NativeCodeModeNotificationDelivery.Delivered -> {
                        put(NATIVE_RESULT_STATUS, "ok")
                        put("value", buildJsonObject { put("type", "notification/delivered") })
                    }
                    is NativeCodeModeNotificationDelivery.Rejected -> {
                        put(NATIVE_RESULT_STATUS, "error")
                        put("message", delivered.message)
                    }
                }
            },
        )
    }
}
