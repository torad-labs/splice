package splice.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import java.io.IOException
import java.nio.file.Path

private const val CODEX_CONFIG_BACKUP_REASON = "Could not create a secure Codex config backup."
private const val CODEX_CONFIG_PERMISSION_REASON = "Codex config permissions could not be secured."
private const val CODEX_CONFIG_WRITE_REASON = "The Codex config API could not update mcp_servers.temple."
private const val CODEX_CONFIG_RELOAD_REASON = "The Codex MCP server config could not be reloaded."
private const val CODEX_CONFIG_ENTRY_REASON = "The existing Codex temple entry is not an object."

internal class NativeCodexMcpConfig(paths: StatePaths) {
    private val files = NativeCodexMcpFiles(paths)
    private val transportFields = setOf(
        "command",
        "args",
        "env",
        "cwd",
        "env_vars",
        "url",
        "headers",
        "http_headers",
        "env_http_headers",
        "bearer_token_env_var",
    )

    suspend fun install(
        session: NativeCodexMcpRpcSession,
        configFile: Path,
        entry: JsonObject,
    ): Boolean {
        val target = files.configTarget(configFile)
        val layer = NativeCodexMcpLayers().read(session, configFile, target)
        val currentEntry = templeEntry(layer.config)
        val restrictions = currentEntry?.filterKeys { it !in transportFields }.orEmpty()
        val desiredEntry = JsonObject(entry + restrictions)
        if (desiredEntry == currentEntry) {
            reload(session)
            return false
        }

        val secure = containsBearerHeaders(layer.config) || containsBearerHeaders(desiredEntry)
        if (!files.prepareWrite(target, secure)) fail(CODEX_CONFIG_BACKUP_REASON)
        writeEntry(session, configFile, layer.version, desiredEntry)
        if (secure && !files.tighten(target)) fail(CODEX_CONFIG_PERMISSION_REASON)
        reload(session)
        return true
    }

    private fun templeEntry(config: JsonObject): JsonObject? =
        objectField(objectField(config, "mcp_servers"), "temple")

    private fun objectField(parent: JsonObject?, key: String): JsonObject? {
        val value = parent?.get(key)
        return when {
            value == null || value == JsonNull -> null
            value is JsonObject -> value
            else -> fail(CODEX_CONFIG_ENTRY_REASON)
        }
    }

    private fun containsBearerHeaders(element: JsonElement): Boolean =
        when (element) {
            is JsonObject ->
                hasBearerHeaderKey(element) || element.values.any { value -> containsBearerHeaders(value) }
            is JsonArray -> element.any { value -> containsBearerHeaders(value) }
            is JsonPrimitive -> false
        }

    private fun hasBearerHeaderKey(objectValue: JsonObject): Boolean =
        objectValue.keys.any { key ->
            key.equals("authorization", ignoreCase = true) ||
                key.equals("bearer_token_env_var", ignoreCase = true)
        }

    private suspend fun writeEntry(
        session: NativeCodexMcpRpcSession,
        configFile: Path,
        version: String,
        entry: JsonObject,
    ) {
        try {
            session.request(
                "config/value/write",
                buildJsonObject {
                    put("keyPath", "mcp_servers.temple")
                    put("value", entry)
                    put("mergeStrategy", "replace")
                    put("filePath", configFile.toString())
                    put("expectedVersion", version)
                },
            )
        } catch (cancelled: CancellationException) {
            rethrow(cancelled)
        } catch (_: NativeCodexMcpRpcFailure) {
            fail(CODEX_CONFIG_WRITE_REASON)
        } catch (_: IOException) {
            fail(CODEX_CONFIG_WRITE_REASON)
        }
    }

    private suspend fun reload(session: NativeCodexMcpRpcSession) {
        try {
            session.request("config/mcpServer/reload")
        } catch (cancelled: CancellationException) {
            rethrow(cancelled)
        } catch (_: NativeCodexMcpRpcFailure) {
            fail(CODEX_CONFIG_RELOAD_REASON)
        } catch (_: IOException) {
            fail(CODEX_CONFIG_RELOAD_REASON)
        }
    }

    private fun rethrow(cancelled: CancellationException): Nothing = throw cancelled

    private fun fail(reason: String): Nothing = throw NativeCodexMcpConfigFailure(reason)
}

internal class NativeCodexMcpConfigFailure(val reason: String) : Exception(reason)
