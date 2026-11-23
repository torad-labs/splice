package splice.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.TEMPLE_MCP_NAME
import splice.core.config.StatePaths
import java.io.IOException
import java.nio.file.Path

private const val CODEX_CONFIG_BACKUP_REASON = "Could not create a secure Codex config backup."
private const val CODEX_CONFIG_PERMISSION_REASON = "Codex config permissions could not be secured."
private const val CODEX_CONFIG_WRITE_REASON = "The Codex config API could not update mcp_servers."
private const val CODEX_CONFIG_RELOAD_REASON = "The Codex MCP server config could not be reloaded."
private const val CODEX_CONFIG_ENTRY_REASON = "An existing Codex mcp_servers entry is not an object."
private const val CODEX_FIELD_COMMAND = "command"
private const val CODEX_FIELD_ENV_VARS = "env_vars"
private const val CODEX_FIELD_HEADERS = "headers"
private const val CODEX_FIELD_HTTP_HEADERS = "http_headers"
private const val CODEX_FIELD_AUTHORIZATION = "authorization"
private const val CODEX_FIELD_BEARER_ENV = "bearer_token_env_var"

internal class NativeCodexMcpConfig(paths: StatePaths) {
    private val files = NativeCodexMcpFiles(paths)
    private val coreTransportFields = setOf(
        CODEX_FIELD_COMMAND,
        "args",
        "env",
        "cwd",
        "url",
        CODEX_FIELD_HEADERS,
        CODEX_FIELD_HTTP_HEADERS,
    )
    private val authEnvFields = setOf(CODEX_FIELD_BEARER_ENV, "env_http_headers")

    suspend fun install(
        session: NativeCodexMcpRpcSession,
        configFile: Path,
        entry: JsonObject,
    ): Boolean = installAll(session, configFile, mapOf(TEMPLE_MCP_NAME to entry))

    suspend fun installAll(
        session: NativeCodexMcpRpcSession,
        configFile: Path,
        entries: Map<String, JsonObject>,
    ): Boolean {
        if (entries.isEmpty()) return false
        val target = files.configTarget(configFile)
        var layer = NativeCodexMcpLayers().read(session, configFile, target)
        val pending = pendingUpdates(layer.config, entries)
        if (pending.isEmpty()) {
            reload(session)
            return false
        }
        val secure = containsBearerHeaders(layer.config) || pending.values.any(::containsBearerHeaders)
        if (!files.prepareWrite(target, secure)) fail(CODEX_CONFIG_BACKUP_REASON)
        pending.entries.forEachIndexed { index, (name, desiredEntry) ->
            if (index > 0) layer = NativeCodexMcpLayers().read(session, configFile, target)
            writeEntry(session, configFile, layer.version, name, desiredEntry)
        }
        if (secure && !files.tighten(target)) fail(CODEX_CONFIG_PERMISSION_REASON)
        reload(session)
        return true
    }

    internal fun pendingUpdates(config: JsonObject, entries: Map<String, JsonObject>): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        entries.forEach { (name, entry) ->
            val currentEntry = serverEntry(config, name)
            val replacesAuth = hasExplicitAuthHeader(entry) || entry.containsKey(CODEX_FIELD_COMMAND)
            val replacesEnvVars = entry.containsKey("url") || entry.containsKey(CODEX_FIELD_ENV_VARS)
            val preserved = currentEntry?.filterKeys { key ->
                keepExistingField(key, replacesAuth, replacesEnvVars)
            }.orEmpty()
            val desiredEntry = JsonObject(entry + preserved)
            if (desiredEntry != currentEntry) out[name] = desiredEntry
        }
        return out
    }

    private fun keepExistingField(key: String, replacesAuth: Boolean, replacesEnvVars: Boolean): Boolean = when {
        key in coreTransportFields -> false
        key in authEnvFields -> !replacesAuth
        key == CODEX_FIELD_ENV_VARS -> !replacesEnvVars
        else -> true
    }

    private fun hasExplicitAuthHeader(entry: JsonObject): Boolean {
        val httpHeaders = entry[CODEX_FIELD_HTTP_HEADERS] as? JsonObject
        val plainHeaders = entry[CODEX_FIELD_HEADERS] as? JsonObject
        val inHttp = httpHeaders?.keys?.any { it.equals(CODEX_FIELD_AUTHORIZATION, ignoreCase = true) } == true
        val inPlain = plainHeaders?.keys?.any { it.equals(CODEX_FIELD_AUTHORIZATION, ignoreCase = true) } == true
        return inHttp || inPlain
    }

    private fun serverEntry(config: JsonObject, name: String): JsonObject? =
        objectField(objectField(config, "mcp_servers"), name)

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
            key.equals(CODEX_FIELD_AUTHORIZATION, ignoreCase = true) ||
                key.equals(CODEX_FIELD_BEARER_ENV, ignoreCase = true)
        }

    private suspend fun writeEntry(
        session: NativeCodexMcpRpcSession,
        configFile: Path,
        version: String,
        name: String,
        entry: JsonObject,
    ) {
        try {
            val keySegment = CodexMcpTomlScan().formatKey(name)
            session.request(
                "config/value/write",
                buildJsonObject {
                    put("keyPath", "mcp_servers.$keySegment")
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
