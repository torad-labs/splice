// Writes Splice MCP servers into Antigravity's `~/.gemini/config/mcp_config.json` atomically (0600),
// backing up the existing file first, tracking Splice-managed server names in `<state>/agy-mcp-managed.json`
// so removed servers are pruned cleanly, removing demonstrably dead entries, and preserving valid
// unrelated keys and user-authored agy-only servers.
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
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

private const val AGY_BACKUP_DIR = "mcp-sync-backups"
private const val AGY_SERVERS_KEY = "mcpServers"

/** Syncs Splice MCP entries into Antigravity's `mcp_config.json` in agy's native `serverUrl`/`headers` shape. */
internal class AgyMcpConfigWriter(
    private val paths: StatePaths,
    private val staleEntries: AgyMcpStaleEntries = AgyMcpStaleEntries(),
    private val log: LogSink = LogSink {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }
    private val managedState = AgyMcpManagedState(paths)
    private val urlAuth = McpUrlAuthNormalizer()
    private val normalizer = McpEntryNormalizer()

    /** Writes [entries] (canonical Splice-planned JSON entries) into [configFile]. Returns true when modified. */
    fun sync(configFile: Path, entries: Map<String, JsonObject>, pruneStale: Boolean = true): Boolean {
        val encodedEntries = encodeAll(entries)
        val previouslyManaged = managedState.read()
        val target = TopologyFileTarget.of(configFile)
        val existingText = readExisting(target)
        if (existingText == null && encodedEntries.isEmpty()) {
            if (previouslyManaged.isNotEmpty()) managedState.write(emptySet())
            return false
        }
        val root = parseExisting(existingText)
        val currentServers = (root[AGY_SERVERS_KEY] as? JsonObject).orEmpty()
        val (mergedServers, changed) = mergeEntries(currentServers, encodedEntries, previouslyManaged, pruneStale)
        val nextManaged = encodedEntries.keys
        if (!changed && existingText != null) {
            if (nextManaged != previouslyManaged) managedState.write(nextManaged)
            return false
        }
        val updatedRoot = buildUpdatedRoot(root, mergedServers)
        val rendered = json.encodeToString(JsonObject.serializer(), updatedRoot) + "\n"
        if (existingText != null) backup(existingText)
        SecureFile.writeAtomic0600(target, rendered)
        managedState.write(nextManaged)
        return true
    }

    private fun encodeAll(entries: Map<String, JsonObject>): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        entries.entries.sortedBy { it.key }.forEach { (name, canonical) ->
            encodeForAgy(canonical)?.let { out[name] = it }
        }
        return out
    }

    private fun parseExisting(existingText: String?): JsonObject {
        if (existingText.isNullOrBlank()) return JsonObject(emptyMap())
        return JsonScalars.objectOrNull(json, existingText)
            ?: throw IOException("Existing agy mcp_config.json is malformed; refusing to overwrite")
    }

    private fun mergeEntries(
        currentServers: Map<String, JsonElement>,
        encodedEntries: Map<String, JsonObject>,
        previouslyManaged: Set<String>,
        pruneStale: Boolean,
    ): Pair<Map<String, JsonElement>, Boolean> {
        val merged = LinkedHashMap<String, JsonElement>(currentServers)
        val removed = pruneUnwanted(merged, encodedEntries.keys, previouslyManaged, pruneStale)
        val upserted = upsertEntries(merged, encodedEntries)
        return merged to (removed || upserted)
    }

    private fun pruneUnwanted(
        merged: MutableMap<String, JsonElement>,
        activeNames: Set<String>,
        previouslyManaged: Set<String>,
        pruneStale: Boolean,
    ): Boolean {
        val removedManaged = mutableListOf<String>()
        val prunedDead = mutableListOf<String>()
        val candidates = merged.entries.filter { it.key !in activeNames }.toList()
        candidates.forEach { (name, element) ->
            val dead = classifyRemoval(name, element as? JsonObject, previouslyManaged, pruneStale) ?: return@forEach
            merged.remove(name)
            if (dead) prunedDead += name else removedManaged += name
        }
        if (removedManaged.isNotEmpty()) {
            log("[mcp-sync] agy: removed ${removedManaged.sorted().joinToString(", ")}\n")
        }
        if (prunedDead.isNotEmpty()) {
            log("[mcp-sync] agy: pruned stale entries ${prunedDead.sorted().joinToString(", ")}\n")
        }
        return removedManaged.isNotEmpty() || prunedDead.isNotEmpty()
    }

    /** Returns false for a removed Splice-managed entry, true for a dead entry, or null to preserve. */
    private fun classifyRemoval(
        name: String,
        obj: JsonObject?,
        previouslyManaged: Set<String>,
        pruneStale: Boolean,
    ): Boolean? {
        val wasManaged = name in previouslyManaged || (obj != null && normalizer.isSpliceLoopbackEntry(obj))
        return when {
            wasManaged -> false
            pruneStale && obj != null && staleEntries.isDead(name, obj) -> true
            else -> null
        }
    }

    private fun upsertEntries(
        merged: MutableMap<String, JsonElement>,
        encodedEntries: Map<String, JsonObject>,
    ): Boolean {
        var changed = false
        encodedEntries.forEach { (name, encoded) ->
            if (merged[name] != encoded) {
                merged[name] = encoded
                changed = true
            }
        }
        return changed
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

    private fun encodeHttp(url: String, headers: JsonObject?): JsonObject {
        val normalized = urlAuth.normalize(url, stringMap(headers))
        return buildJsonObject {
            put("disabled", false)
            if (normalized.headers.isNotEmpty()) {
                putJsonObject("headers") {
                    normalized.headers.entries.sortedBy { it.key }.forEach { (k, v) -> put(k, v) }
                }
            }
            put("serverUrl", normalized.url)
        }
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
