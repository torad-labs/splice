// Normalizes raw JSON MCP entries from Claude Code, Claude Desktop, and Antigravity (agy) into
// Splice's canonical Claude MCP JSON shape, filtering out disabled and circular Splice-hosted entries.
package splice.app.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.app.MCP_HUB_FRONT_PORT
import splice.client.mcp.MCP_HEAD_HEADER
import splice.core.config.Knob
import splice.core.util.JsonScalars
import java.net.URI
import java.net.URISyntaxException

private const val LOOPBACK_IP = "127.0.0.1"
private const val LOOPBACK_HOST = "localhost"
private const val LOOPBACK_V6 = "::1"
private const val LOOPBACK_V6_BRACKETED = "[::1]"
private const val SPLICE_MCP_PATH_PREFIX = "/mcp/"
private const val SPLICE_LAUNCH_HEADER = "X-Splice-Launch"
private const val NORMALIZE_HEADERS = "headers"
private const val NORMALIZE_COMMAND = "command"

/** Converts harness-specific JSON MCP declarations into canonical Splice entries. */
internal class McpEntryNormalizer(
    private val splicePorts: Set<Int> = setOf(MCP_HUB_FRONT_PORT, Knob.CONTROL_PORT.count().toInt()),
) {
    private val urlAuth = McpUrlAuthNormalizer()

    fun normalize(obj: JsonObject, agyShape: Boolean): JsonObject? {
        if (isDisabled(obj)) return null
        val url = JsonScalars.str(obj, "url")
            ?: if (agyShape) JsonScalars.str(obj, "serverUrl") ?: JsonScalars.str(obj, "httpUrl") else null
        val type = JsonScalars.str(obj, "type")
        val command = JsonScalars.str(obj, NORMALIZE_COMMAND)
        val canonical = when {
            !url.isNullOrBlank() -> normalizeHttp(url, type, obj[NORMALIZE_HEADERS] as? JsonObject)
            !command.isNullOrBlank() -> normalizeStdio(command, obj)
            else -> null
        }
        return canonical?.takeUnless(::isSpliceLoopbackEntry)
    }

    /** Prevents circular self-import of entries Splice itself wrote into agy or Codex. */
    fun isSpliceLoopbackEntry(entry: JsonObject): Boolean {
        val headers = entry[NORMALIZE_HEADERS] as? JsonObject
        if (hasSpliceHeader(headers)) return true
        val rawUrl = JsonScalars.str(entry, "url")
            ?: JsonScalars.str(entry, "serverUrl")
            ?: JsonScalars.str(entry, "httpUrl")
            ?: return false
        return matchesSpliceLoopbackUrl(rawUrl)
    }

    private fun hasSpliceHeader(headers: JsonObject?): Boolean {
        if (headers == null) return false
        return headers.keys.any { key ->
            key.equals(MCP_HEAD_HEADER, ignoreCase = true) || key.equals(SPLICE_LAUNCH_HEADER, ignoreCase = true)
        }
    }

    private fun matchesSpliceLoopbackUrl(rawUrl: String): Boolean = try {
        val uri = URI(rawUrl)
        val host = uri.host?.lowercase()
        val loopback = host in setOf(LOOPBACK_IP, LOOPBACK_HOST, LOOPBACK_V6, LOOPBACK_V6_BRACKETED)
        val onSplicePort = uri.port in splicePorts
        loopback && onSplicePort && uri.path.orEmpty().startsWith(SPLICE_MCP_PATH_PREFIX)
    } catch (_: URISyntaxException) {
        val lower = rawUrl.lowercase()
        splicePorts.any { port ->
            lower.startsWith("http://$LOOPBACK_IP:$port$SPLICE_MCP_PATH_PREFIX") ||
                lower.startsWith("http://$LOOPBACK_HOST:$port$SPLICE_MCP_PATH_PREFIX") ||
                lower.startsWith("http://$LOOPBACK_V6_BRACKETED:$port$SPLICE_MCP_PATH_PREFIX")
        }
    }

    private fun normalizeHttp(url: String, type: String?, headers: JsonObject?): JsonObject {
        val normalized = urlAuth.normalize(url, stringMap(headers))
        return buildJsonObject {
            put("type", if (type == "sse") "sse" else "http")
            put("url", normalized.url)
            if (normalized.headers.isNotEmpty()) {
                putJsonObject(NORMALIZE_HEADERS) { normalized.headers.forEach { (k, v) -> put(k, v) } }
            }
        }
    }

    private fun normalizeStdio(command: String, obj: JsonObject): JsonObject = buildJsonObject {
        put(NORMALIZE_COMMAND, command)
        val args = (obj["args"] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str)
        if (args.isNotEmpty()) {
            put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
        }
        JsonScalars.str(obj, "cwd")?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
        val envMap = stringMap(obj["env"] as? JsonObject)
        if (envMap.isNotEmpty()) {
            putJsonObject("env") { envMap.forEach { (k, v) -> put(k, v) } }
        }
    }

    private fun stringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.forEach { (k, v) -> JsonScalars.str(v)?.let { out[k] = it } }
        return out
    }

    private fun isDisabled(obj: JsonObject): Boolean =
        (obj["disabled"] as? JsonPrimitive)?.booleanOrNull == true ||
            (obj["enabled"] as? JsonPrimitive)?.booleanOrNull == false
}
