// NEW: agy-desktop-parity-2 (docs/specs/desktop-antigravity.md). MCP server specifications read from Claude Desktop's
// `initialize` / `mcp_set_servers` and from Claude Code's config files, encoded into ACP's `mcpServers` shapes without
// ever logging env vars or header values.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.util.JsonScalars
import splice.core.util.LogSink

/** One MCP server configured for a Desktop session on agy. */
internal sealed class AgyMcpSpec {
    abstract val name: String

    data class Stdio(
        override val name: String,
        val command: String,
        val args: List<String>,
        val env: Map<String, String>,
    ) : AgyMcpSpec()

    data class Remote(
        override val name: String,
        val type: String,
        val url: String,
        val headers: Map<String, String>,
    ) : AgyMcpSpec()

    data class Sdk(override val name: String) : AgyMcpSpec()
}

/** Transforms string values in a parsed MCP config (e.g. `${VAR:-default}` expansion). */
internal interface McpValueTransform {
    fun apply(value: String): String
}

/** Reads Desktop's `mcpServers` / `sdkMcpServers` / `servers` entries into [AgyMcpSpec] values. */
internal class AgyMcpReader(private val log: LogSink) {
    private val identity = object : McpValueTransform {
        override fun apply(value: String): String = value
    }

    fun fromInitialize(request: JsonObject): List<AgyMcpSpec> {
        val out = LinkedHashMap<String, AgyMcpSpec>()
        readMap(request["mcpServers"] as? JsonObject, out, identity)
        (request["sdkMcpServers"] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str)
            .filter(String::isNotBlank)
            .forEach { name -> out.putIfAbsent(name, AgyMcpSpec.Sdk(name)) }
        return out.values.toList()
    }

    fun fromSetServers(request: JsonObject): List<AgyMcpSpec> {
        val out = LinkedHashMap<String, AgyMcpSpec>()
        readMap(request["servers"] as? JsonObject, out, identity)
        return out.values.toList()
    }

    fun fromConfigMap(servers: JsonObject?, transform: McpValueTransform = identity): List<AgyMcpSpec> {
        val out = LinkedHashMap<String, AgyMcpSpec>()
        readMap(servers, out, transform)
        return out.values.toList()
    }

    private fun readMap(
        servers: JsonObject?,
        out: LinkedHashMap<String, AgyMcpSpec>,
        transform: McpValueTransform,
    ) {
        if (servers == null) return
        servers.forEach { (name, value) ->
            val obj = value as? JsonObject ?: return@forEach
            parseEntry(name, obj, transform)?.let { out[name] = it }
        }
    }

    private fun parseEntry(name: String, obj: JsonObject, transform: McpValueTransform): AgyMcpSpec? {
        val type = JsonScalars.str(obj, "type")
        return when (type) {
            null, "stdio" -> {
                val command = JsonScalars.str(obj, "command")?.let(transform::apply)
                if (command.isNullOrBlank()) {
                    logUnsupported(name, type ?: "stdio")
                    null
                } else {
                    val args = (obj["args"] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str).map(transform::apply)
                    val env = stringMap(obj["env"] as? JsonObject, transform)
                    AgyMcpSpec.Stdio(name, command, args, env)
                }
            }
            "http", "sse" -> {
                val url = JsonScalars.str(obj, "url")?.let(transform::apply)
                if (url.isNullOrBlank()) {
                    logUnsupported(name, type)
                    null
                } else {
                    AgyMcpSpec.Remote(name, type, url, stringMap(obj["headers"] as? JsonObject, transform))
                }
            }
            "sdk" -> AgyMcpSpec.Sdk(name)
            else -> {
                logUnsupported(name, type)
                null
            }
        }
    }

    private fun stringMap(obj: JsonObject?, transform: McpValueTransform): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.forEach { (k, v) -> JsonScalars.str(v)?.let { out[k] = transform.apply(it) } }
        return out
    }

    private fun logUnsupported(name: String, type: String) {
        log("[antigravity] skipping unsupported MCP server '$name' (type=$type)\n")
    }
}

/** Encodes [AgyMcpSpec] values into ACP's `mcpServers` JSON array shape. */
internal class AcpMcpEncoder {
    fun encode(specs: List<AgyMcpSpec>, bridge: McpBridge?): JsonArray = buildJsonArray {
        specs.forEach { spec ->
            encodeOne(spec, bridge)?.let(::add)
        }
    }

    private fun encodeOne(spec: AgyMcpSpec, bridge: McpBridge?): JsonObject? = when (spec) {
        is AgyMcpSpec.Stdio -> buildJsonObject {
            put("name", spec.name)
            put("command", spec.command)
            put("args", JsonArray(spec.args.map(::JsonPrimitive)))
            put("env", pairs(spec.env))
        }
        is AgyMcpSpec.Remote -> buildJsonObject {
            put("name", spec.name)
            put("type", spec.type)
            put("url", spec.url)
            put("headers", pairs(spec.headers))
        }
        is AgyMcpSpec.Sdk -> bridge?.let { b ->
            buildJsonObject {
                put("name", spec.name)
                put("type", "http")
                put("url", b.urlFor(spec.name))
                putJsonArray("headers") {
                    add(
                        buildJsonObject {
                            put("name", "Authorization")
                            put("value", b.authHeader())
                        },
                    )
                }
            }
        }
    }

    private fun pairs(map: Map<String, String>): JsonArray = buildJsonArray {
        map.forEach { (k, v) ->
            add(
                buildJsonObject {
                    put("name", k)
                    put("value", v)
                },
            )
        }
    }
}
