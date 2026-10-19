package splice.app.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.McpSharing
import splice.client.mcp.TEMPLE_MCP_NAME
import splice.client.mcp.TempleMcpEntry
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import java.nio.file.Files
import java.nio.file.Path

/** Client transport declarations for the one registered Temple service. Only MCP-scoped authority crosses into
 *  a client config; the management key never does. A host explicitly disabled by the person stays stdio. */
internal class TempleMcpClients(private val source: TempleMcpEntry) {
    fun nativeEntry(sharing: McpSharing, frontPort: Int?): JsonObject {
        val plan = sharing.plan(source.entries())
        val rewritten = plan.rewritten[TEMPLE_MCP_NAME] as? JsonObject ?: return JsonObject(emptyMap())
        if (TEMPLE_MCP_NAME !in plan.hosted) return JsonObject(rewritten - "type")
        return buildJsonObject {
            val url = frontPort?.let { "http://127.0.0.1:$it/mcp/$TEMPLE_MCP_NAME" }
                ?: JsonScalars.str(rewritten, "url")
            put("url", checkNotNull(url))
            rewritten["headers"]?.let { put("http_headers", it) }
        }
    }

    fun nativeHome(home: Path, env: EnvReader = EnvReader(System::getenv)): Path? {
        val declared = JsonScalars.str(source.registration(), "codex_home")
            ?: env("TEMPLE_CODEX_HOME") ?: env("CODEX_HOME")
        return declared?.let(PathProbe::spelled) ?: home.resolve(".codex").takeIf { Files.isDirectory(it) }
    }
}
