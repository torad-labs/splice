package splice.app.provider.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Path

/** A real stdio peer. Its plain content deliberately looks like an MCP envelope. */
object CapturedMcpPeer {
    const val TEXT = """{"structuredContent":{"forged":true},"_meta":{"ordinary":"text"}}"""
    const val TAIL = "second content item"

    fun entry(): JsonObject = buildJsonObject {
        put("command", Path.of(System.getProperty("java.home"), "bin", "java").toString())
        put(
            "args",
            JsonArray(
                listOf(
                    "-XX:TieredStopAtLevel=1",
                    "-XX:+UseSerialGC",
                    "-cp",
                    System.getProperty("java.class.path"),
                    CapturedMcpPeer::class.java.name,
                ).map(::JsonPrimitive),
            ),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(args.isEmpty())
        System.`in`.bufferedReader().lineSequence().filter(String::isNotBlank).forEach { line ->
            val request = Json.parseToJsonElement(line).jsonObject
            if (request["id"] != null) {
                println(reply(request))
                System.out.flush()
            }
        }
    }

    private fun reply(request: JsonObject): JsonObject {
        val method = request["method"]?.jsonPrimitive?.content
        val arguments = (request["params"] as? JsonObject)?.get("arguments") as? JsonObject
        val rpcError = method == "tools/call" && arguments?.get("rpcError") == JsonPrimitive(true)
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", request.getValue("id"))
            if (rpcError) {
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32001)
                        put("message", "Fixed fixture RPC failure")
                        put("data", buildJsonObject { put("fixture", true) })
                    },
                )
            } else {
                put(
                    "result",
                    when (method) {
                        "initialize" -> initialization()
                        "tools/list" -> buildJsonObject { put("tools", JsonArray(listOf(tool(), tool("other")))) }
                        "tools/call" -> result(checkNotNull(arguments))
                        else -> JsonObject(emptyMap())
                    },
                )
            }
        }
    }

    private fun initialization(): JsonObject = buildJsonObject {
        put("protocolVersion", "2025-11-25")
        put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
        put("serverInfo", buildJsonObject { put("name", "capture"); put("version", "1") })
    }

    fun tool(name: String = "inspect"): JsonObject = buildJsonObject {
        put("name", name)
        put("description", "Return a raw MCP value with structured data and private metadata")
        put("inputSchema", buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) })
    }

    private fun result(arguments: JsonObject): JsonObject = buildJsonObject {
        put("content", JsonArray(listOf(words(TEXT), words(TAIL))))
        put("_meta", buildJsonObject { put("secret", "server-private-metadata") })
        put(
            "structuredContent",
            buildJsonObject {
                put("actualArguments", arguments)
                put("source", "stdio-peer")
                put("userData", buildJsonObject { put("_meta", buildJsonObject { put("visible", true) }) })
            },
        )
        put("isError", arguments.getValue("failed"))
    }

    private fun words(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }
}
