package splice.client.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.auth.BearerScheme
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** The publication half of a preflighted selective header update. */
public fun interface McpHeaderPublication {
    public operator fun invoke(replacement: String)
}

/** One preflighted header file. Contains private snapshots, so deliberately not a data class.
 *  [backupFile] is an owner-only copy in state, not alongside an operator's file or in a repository. */
public class McpHeaderUpdate internal constructor(
    public val file: Path,
    public val matchedHeaders: Int,
    public val backupFile: Path?,
    private val writer: McpHeaderPublication,
) {
    /** Publishes only matching MCP headers, refusing a file changed since preflight. */
    public fun publish(replacement: String): Unit = writer(replacement)
}

/** Selective regeneration, not a full materialization: grants, hooks, unrelated headers, endpoints
 *  and server definitions remain unchanged. Only exact revoked bearers at these MCP prefixes match.
 *  Callers select files explicitly; no home/global profile scan or implicit write is performed. */
public class McpHeaderRegeneration(
    private val previous: String,
    private val endpointPrefixes: Set<String>,
    private val backupDir: Path,
) {
    /** Strict read and backup before revocation. A malformed, linked or foreign-owned file aborts
     *  preflight. Files with no matching header are never backed up or rewritten. */
    public fun prepare(path: Path): McpHeaderUpdate {
        val file = path.toAbsolutePath().normalize()
        requireOwnerFile(file)
        val original = Files.readString(file)
        val document = Json.parseToJsonElement(original) as? JsonObject
            ?: throw IllegalArgumentException("MCP header file must be a JSON object")
        val count = rewrite(document, previous).second
        if (count == 0) return McpHeaderUpdate(file, 0, null) { }
        return McpHeaderUpdate(file, count, backup(original)) { replacement ->
            publish(file, original, document, replacement)
        }
    }

    private fun requireOwnerFile(file: Path) {
        val regular = Files.readAttributes(file, "basic:isRegularFile", NOFOLLOW_LINKS)["isRegularFile"] == true
        val owned = Files.getOwner(file, NOFOLLOW_LINKS) == Files.getOwner(backupDir.parent, NOFOLLOW_LINKS)
        if (!regular || !owned) throw IOException("MCP header file is not an owner-held regular file")
    }

    private fun backup(original: String): Path {
        if (Files.isSymbolicLink(backupDir)) throw IOException("MCP header backup directory must not be a link")
        SecureFile.ownerOnlyDirectory(backupDir)?.let {
            throw IOException("MCP header backup directory is not owner-only")
        }
        val file = backupDir.resolve(UUID.randomUUID().toString() + ".json")
        SecureFile.createNew0600(file, original.toByteArray(Charsets.UTF_8))
        return file
    }

    private fun publish(file: Path, original: String, document: JsonObject, replacement: String) {
        requireOwnerFile(file)
        if (Files.readString(file) != original) throw IOException("MCP header file changed since preflight")
        SecureFile.writeAtomic0600(file, rewrite(document, replacement).first.toString() + "\n")
    }

    private fun rewrite(document: JsonObject, replacement: String): Pair<JsonObject, Int> {
        val root = scope(document, replacement)
        val projects = document["projects"] as? JsonObject ?: return root
        var count = root.second
        val rewritten = projects.mapValues { (_, value) ->
            val project = value as? JsonObject
            if (project == null) {
                value
            } else {
                val result = scope(project, replacement)
                count += result.second
                result.first
            }
        }
        return JsonObject(root.first + ("projects" to JsonObject(rewritten))) to count
    }

    private fun scope(document: JsonObject, replacement: String): Pair<JsonObject, Int> {
        val servers = document["mcpServers"] as? JsonObject ?: return document to 0
        var count = 0
        val rewritten = servers.mapValues { (_, value) ->
            val entry = value as? JsonObject
            if (entry == null || !hosted(entry)) {
                value
            } else {
                val result = headers(entry, replacement)
                count += result.second
                result.first
            }
        }
        return JsonObject(document + ("mcpServers" to JsonObject(rewritten))) to count
    }

    private fun hosted(entry: JsonObject): Boolean {
        val url = JsonScalars.str(entry, "url") ?: return false
        if (JsonScalars.str(entry, "type") !in setOf("http", "sse")) return false
        return endpointPrefixes.any { url.startsWith(it) && url.length > it.length }
    }

    private fun headers(entry: JsonObject, replacement: String): Pair<JsonObject, Int> {
        val headers = entry["headers"] as? JsonObject ?: return entry to 0
        var count = 0
        val updated = headers.mapValues { (name, header) ->
            if (revoked(name, header)) {
                count++
                JsonPrimitive("Bearer $replacement")
            } else {
                header
            }
        }
        return JsonObject(entry + ("headers" to JsonObject(updated))) to count
    }

    private fun revoked(name: String, header: JsonElement): Boolean =
        name.equals("Authorization", ignoreCase = true) && BearerScheme.bearerToken(JsonScalars.str(header)) == previous
}
