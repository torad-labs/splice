// Writes Splice MCP servers into Antigravity's `~/.gemini/config/mcp_config.json` atomically (0600),
// backing up the existing file first and preserving all unrelated keys and agy-only servers.
package splice.app.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.config.StatePaths
import splice.core.mcp.MCP_BACKUP_HASH_CHARS
import splice.core.topology.TopologyFileTarget
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

private const val AGY_BACKUP_DIR = "mcp-sync-backups"
private const val AGY_SERVERS_KEY = "mcpServers"

/** Syncs Splice MCP entries into Antigravity's `mcp_config.json` in agy's native `serverUrl`/`headers` shape. */
internal class AgyMcpConfigWriter(private val paths: StatePaths) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    /** Writes [entries] (canonical Splice-planned JSON entries) into [configFile]. Returns true when modified. */
    fun sync(configFile: Path, entries: Map<String, JsonObject>): Boolean {
        if (entries.isEmpty()) return false
        val target = TopologyFileTarget.of(configFile)
        val existingText = readExisting(target)
        val root = parseExisting(existingText)
        val currentServers = (root[AGY_SERVERS_KEY] as? JsonObject).orEmpty()
        val (mergedServers, changed) = mergeEntries(currentServers, entries)
        if (!changed && existingText != null) return false
        val updatedRoot = buildUpdatedRoot(root, mergedServers)
        val rendered = json.encodeToString(JsonObject.serializer(), updatedRoot) + "\n"
        if (existingText != null) backup(existingText)
        SecureFile.writeAtomic0600(target, rendered)
        return true
    }

    private fun parseExisting(existingText: String?): JsonObject {
        if (existingText.isNullOrBlank()) return JsonObject(emptyMap())
        return JsonScalars.objectOrNull(json, existingText)
            ?: throw IOException("Existing agy mcp_config.json is malformed; refusing to overwrite")
    }

    private fun mergeEntries(
        currentServers: Map<String, JsonElement>,
        entries: Map<String, JsonObject>,
    ): Pair<Map<String, JsonElement>, Boolean> {
        val merged = LinkedHashMap<String, JsonElement>(currentServers)
        var changed = false
        entries.entries.sortedBy { it.key }.forEach { (name, canonical) ->
            val encoded = encodeForAgy(canonical)
            if (encoded != null && merged[name] != encoded) {
                merged[name] = encoded
                changed = true
            }
        }
        return merged to changed
    }

    private fun buildUpdatedRoot(root: JsonObject, mergedServers: Map<String, JsonElement>): JsonObject =
        buildJsonObject {
            root.filterKeys { it != AGY_SERVERS_KEY }.forEach { (k, v) -> put(k, v) }
            putJsonObject(AGY_SERVERS_KEY) {
                mergedServers.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, v) }
            }
        }

    /** Encodes a canonical Claude/Splice MCP entry into agy's `mcp_config.json` schema. */
    fun encodeForAgy(canonical: JsonObject): JsonObject? {
        val url = JsonScalars.str(canonical, "url")
        val command = JsonScalars.str(canonical, "command")
        return when {
            !url.isNullOrBlank() -> encodeHttp(url, canonical["headers"] as? JsonObject)
            !command.isNullOrBlank() -> encodeStdio(command, canonical)
            else -> null
        }
    }

    private fun encodeHttp(url: String, headers: JsonObject?): JsonObject = buildJsonObject {
        put("disabled", false)
        val cleanHeaders = stringMap(headers)
        if (cleanHeaders.isNotEmpty()) {
            putJsonObject("headers") {
                cleanHeaders.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, v) }
            }
        }
        put("serverUrl", url)
    }

    private fun encodeStdio(command: String, canonical: JsonObject): JsonObject = buildJsonObject {
        val args = (canonical["args"] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str)
        if (args.isNotEmpty()) {
            put("args", JsonArray(args.map(::JsonPrimitive)))
        }
        put("command", command)
        JsonScalars.str(canonical, "cwd")?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
        put("disabled", false)
        val env = stringMap(canonical["env"] as? JsonObject)
        if (env.isNotEmpty()) {
            putJsonObject("env") {
                env.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    private fun stringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.forEach { (k, v) -> JsonScalars.str(v)?.let { out[k] = it } }
        return out
    }

    private fun readExisting(file: Path): String? =
        if (!Files.isRegularFile(file)) null else Files.readString(file)

    private fun backup(previous: String) {
        val backupDir = paths.stateDir.resolve(AGY_BACKUP_DIR)
        if (Files.isSymbolicLink(backupDir)) throw IOException("MCP sync backup directory must not be a link")
        SecureFile.ownerOnlyDirectory(backupDir)?.let { reason ->
            throw IOException("MCP sync backup directory is not owner-only: $reason")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(previous.toByteArray(Charsets.UTF_8))
        val hash = HexFormat.of().formatHex(digest).take(MCP_BACKUP_HASH_CHARS)
        val target = backupDir.resolve("agy-mcp_config.json.bak-$hash")
        if (!Files.exists(target)) SecureFile.writeAtomic0600(target, previous)
    }
}
