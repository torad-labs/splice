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
import splice.upstream.codemode.NativeCodeModeOptions

private const val NATIVE_METHOD: String = "method"
private const val NATIVE_SESSION_ID: String = "sessionId"

internal object NativeRequests {
    fun session(method: String, sessionId: String): JsonObject = buildJsonObject {
        put(NATIVE_METHOD, method)
        put(NATIVE_SESSION_ID, sessionId)
    }

    fun open(sessionId: String, heapBytes: Long): JsonObject = buildJsonObject {
        put(NATIVE_METHOD, "session/open")
        put(NATIVE_SESSION_ID, sessionId)
        put("cellExecutionLimits", buildJsonObject { put("maxHeapSizeBytes", heapBytes) })
    }

    fun execute(sessionId: String, source: String, catalog: Map<String, String>, options: NativeCodeModeOptions) =
        buildJsonObject {
            put(NATIVE_METHOD, "session/execute")
            put(NATIVE_SESSION_ID, sessionId)
            put(
                "request",
                buildJsonObject {
                    put("tool_call_id", java.util.UUID.randomUUID().toString())
                    put(
                        "enabled_tools",
                        buildJsonArray {
                            catalog.forEach { (name, description) ->
                                add(tool(name, description))
                            }
                        },
                    )
                    put("source", source)
                    put("yield_time_ms", options.yieldTimeMs)
                    put("max_output_tokens", options.maxOutputTokens)
                },
            )
        }

    private fun tool(name: String, description: String): JsonObject = buildJsonObject {
        put("name", CodeModeManual.identifier(name))
        put(
            "tool_name",
            buildJsonObject {
                put("name", name)
                put("namespace", JsonNull)
            },
        )
        put("description", description)
        put("kind", "function")
        put("input_schema", JsonNull)
        put("output_schema", JsonNull)
    }

    fun wait(sessionId: String, cellId: String, yieldTimeMs: Long): JsonObject = buildJsonObject {
        put(NATIVE_METHOD, "session/wait")
        put(NATIVE_SESSION_ID, sessionId)
        put(
            "request",
            buildJsonObject {
                put("cell_id", cellId)
                put("yield_time_ms", yieldTimeMs)
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
                if (result.isError) {
                    put("status", "error")
                    put("message", result.output)
                } else {
                    put("status", "ok")
                    put(
                        "value",
                        buildJsonObject {
                            put("type", "tool/result")
                            put("result", result.payload ?: JsonPrimitive(result.output))
                        },
                    )
                }
            },
        )
    }

    fun notified(id: JsonElement): JsonObject = buildJsonObject {
        put("type", "delegate/response")
        put("id", id)
        put(
            "result",
            buildJsonObject {
                put("status", "ok")
                put("value", buildJsonObject { put("type", "notification/delivered") })
            },
        )
    }
}
