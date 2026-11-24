// Persists the set of MCP server names Splice manages in Antigravity's `~/.gemini/config/mcp_config.json`
// under Splice's own state dir (`<state>/agy-mcp-managed.json`) so Splice can safely remove entries
// when they leave the source set without mutating agy's JSON schema or deleting user-authored servers.
package splice.app.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal const val AGY_MANAGED_FILE_NAME: String = "agy-mcp-managed.json"

// why: schema version 1 of the persisted agy-mcp-managed.json state file.
private const val AGY_MANAGED_SCHEMA_VERSION = 1
private const val AGY_MANAGED_VERSION_KEY = "version"
private const val AGY_MANAGED_SERVERS_KEY = "managed"

/** Owner-only state record of server names Splice currently manages in agy's `mcp_config.json`. */
internal class AgyMcpManagedState(paths: StatePaths) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    val file: Path = paths.stateDir.resolve(AGY_MANAGED_FILE_NAME)

    /** Tolerant read: absent, unreadable, or malformed state reads as an empty managed set. */
    fun read(): Set<String> {
        val arr = readTextOrNull()
            ?.let { JsonScalars.objectOrNull(json, it) }
            ?.get(AGY_MANAGED_SERVERS_KEY) as? JsonArray
        return arr.orEmpty().mapNotNull(JsonScalars::str).filter { it.isNotBlank() }.toSet()
    }

    /** Atomically writes [managed] (0600) only when changed; never creates an empty file from scratch. */
    fun write(managed: Set<String>) {
        val sorted = managed.filter { it.isNotBlank() }.sorted()
        val existing = readTextOrNull()
        if (existing == null && sorted.isEmpty()) return
        val rendered = json.encodeToString(JsonObject.serializer(), encode(sorted)) + "\n"
        if (existing == rendered) return
        SecureFile.writeAtomic0600(file, rendered)
    }

    private fun readTextOrNull(): String? = try {
        if (Files.isRegularFile(file)) Files.readString(file) else null
    } catch (_: IOException) {
        null
    }

    private fun encode(sorted: List<String>): JsonObject = buildJsonObject {
        put(AGY_MANAGED_VERSION_KEY, AGY_MANAGED_SCHEMA_VERSION)
        put(
            AGY_MANAGED_SERVERS_KEY,
            buildJsonArray { sorted.forEach { add(JsonPrimitive(it)) } },
        )
    }
}
