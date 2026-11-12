package splice.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val CODEX_CONFIG_READ_REASON = "Could not read the Codex user configuration layer."
private const val CODEX_CONFIG_LAYER_REASON = "The Codex user configuration layer for this home was not found."

/** Resolves the unprofiled user layer and its optimistic version through Codex's own config API. */
internal class NativeCodexMcpLayers {
    suspend fun read(session: NativeCodexMcpRpcSession, configFile: Path, target: Path): NativeCodexMcpLayer {
        val response = try {
            session.request("config/read", buildJsonObject { put("includeLayers", true) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NativeCodexMcpRpcFailure) {
            fail(CODEX_CONFIG_READ_REASON)
        } catch (_: IOException) {
            fail(CODEX_CONFIG_READ_REASON)
        }
        val root = response as? JsonObject ?: fail(CODEX_CONFIG_READ_REASON)
        val layers = root["layers"] as? JsonArray
        return layers?.firstNotNullOfOrNull { element -> parse(element, configFile, target) }
            ?: fail(CODEX_CONFIG_LAYER_REASON)
    }

    private fun parse(element: JsonElement, configFile: Path, target: Path): NativeCodexMcpLayer? {
        val layer = element as? JsonObject
        val source = layer?.get("name") as? JsonObject
        val sourceType = JsonScalars.str(source, "type")
        val profile = JsonScalars.str(source, "profile")
        val layerPath = pathOrNull(JsonScalars.str(source, "file"))
        val config = layer?.get("config") as? JsonObject
        val version = JsonScalars.str(layer, "version")
        return when {
            sourceType != "user" -> null
            profile != null -> null
            layerPath == null -> null
            layerPath != configFile && layerPath != target -> null
            config == null -> null
            version == null -> null
            else -> NativeCodexMcpLayer(config, version)
        }
    }

    private fun fail(reason: String): Nothing = throw NativeCodexMcpConfigFailure(reason)

    private fun pathOrNull(file: String?): Path? =
        try {
            file?.let { Path.of(it).toAbsolutePath().normalize() }
        } catch (_: InvalidPathException) {
            null
        }
}

internal data class NativeCodexMcpLayer(val config: JsonObject, val version: String)
