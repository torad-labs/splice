// Opt-in canonical user MCP registry stored in Splice's state folder (`mcp-registry.json`).
// Holds MCP server definitions the user explicitly imported from Claude Code, Claude Desktop,
// Antigravity (agy), or Codex so Splice can host and sync them across every connected harness.
package splice.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.config.StatePaths
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

public const val MCP_REGISTRY_FILE_NAME: String = "mcp-registry.json"

// why: 12 hex characters of SHA-256 give content-addressed MCP backups a compact, collision-resistant suffix.
public const val MCP_BACKUP_HASH_CHARS: Int = 12

// why: schema version 1 of the persisted mcp-registry.json envelope.
private const val MCP_REGISTRY_SCHEMA_VERSION = 1

private const val MCP_REGISTRY_BACKUP_DIR = "mcp-registry-backups"
private const val MCP_REG_FIELD_VERSION = "version"
private const val MCP_REG_FIELD_SERVERS = "servers"
private const val MCP_REG_FIELD_HARNESSES = "harnesses"
private const val MCP_REG_FIELD_ENTRY = "entry"
private const val MCP_REG_FIELD_SOURCE = "source"
private const val MCP_REG_FIELD_IMPORTED_AT = "imported_at"
private const val WIRE_CLAUDE = "claude"
private const val WIRE_CLAUDE_CODE = "claude-code"
private const val WIRE_CLAUDE_DESKTOP = "claude-desktop"
private const val WIRE_AGY = "agy"
private const val WIRE_CODEX = "codex"
private const val WIRE_MANUAL = "manual"

private val USERINFO_PATTERN = Regex("(^[A-Za-z][A-Za-z0-9+.-]*://)[^/@]+@")

/** Harnesses that Splice MCP can connect to and synchronize. */
public enum class McpHarness(public val wire: String, public val displayName: String) {
    CLAUDE(WIRE_CLAUDE, "Claude Code / Desktop"),
    AGY(WIRE_AGY, "Antigravity (agy)"),
    CODEX(WIRE_CODEX, "Codex"),
}

/** Where a user MCP server definition was imported from. */
public enum class McpImportSource(public val wire: String) {
    CLAUDE_CODE(WIRE_CLAUDE_CODE),
    CLAUDE_DESKTOP(WIRE_CLAUDE_DESKTOP),
    AGY(WIRE_AGY),
    CODEX(WIRE_CODEX),
    MANUAL(WIRE_MANUAL),
}

/** One server stored in the user's Splice MCP registry. [entry] uses canonical Claude MCP JSON shape. */
public data class McpRegistryRecord(
    val name: String,
    val entry: JsonObject,
    val source: McpImportSource,
    val importedAt: String? = null,
)

/** Snapshot of `<state>/mcp-registry.json`. */
public data class McpRegistrySnapshot(
    val servers: Map<String, McpRegistryRecord> = emptyMap(),
    val harnesses: Set<McpHarness> = emptySet(),
) {
    /** Canonical `name -> entry` JSON map consumed by Splice's MCP planners and host. */
    public fun entryMap(): JsonObject = JsonObject(servers.mapValues { (_, record) -> record.entry })
}

/** Parsing helpers for harness and source wire names, plus credential-safe URL sanitization. */
public class McpRegistryNames {
    public fun harness(wire: String): McpHarness? = when (wire.trim().lowercase()) {
        WIRE_CLAUDE, WIRE_CLAUDE_CODE, WIRE_CLAUDE_DESKTOP -> McpHarness.CLAUDE
        WIRE_AGY, "antigravity" -> McpHarness.AGY
        WIRE_CODEX -> McpHarness.CODEX
        else -> null
    }

    public fun source(wire: String): McpImportSource? = when (wire.trim().lowercase()) {
        WIRE_CLAUDE_CODE, WIRE_CLAUDE -> McpImportSource.CLAUDE_CODE
        WIRE_CLAUDE_DESKTOP, "desktop" -> McpImportSource.CLAUDE_DESKTOP
        WIRE_AGY, "antigravity" -> McpImportSource.AGY
        WIRE_CODEX -> McpImportSource.CODEX
        WIRE_MANUAL -> McpImportSource.MANUAL
        else -> null
    }

    /** Strips userinfo (`user:pass@`), query parameters, and fragments so URL credentials never leak. */
    public fun redactUrl(raw: String?): String? {
        if (raw == null) return null
        return try {
            val uri = URI(raw)
            if (uri.scheme != null && uri.host != null) {
                URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
            } else {
                fallbackRedact(raw)
            }
        } catch (_: URISyntaxException) {
            fallbackRedact(raw)
        }
    }

    private fun fallbackRedact(raw: String): String =
        USERINFO_PATTERN.replace(raw.substringBefore('?').substringBefore('#'), "$1")
}

/** Reads and persists `<state>/mcp-registry.json` atomically with owner-only permissions. */
public class McpUserRegistry(private val paths: StatePaths) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val names = McpRegistryNames()

    public val file: Path = paths.stateDir.resolve(MCP_REGISTRY_FILE_NAME)

    /** Tolerant read: absent, unreadable or malformed registry reads as empty. */
    public fun read(): McpRegistrySnapshot {
        val text = readTextOrNull()
        val root = text?.let { JsonScalars.objectOrNull(json, it) }
        return if (root == null) {
            McpRegistrySnapshot()
        } else {
            McpRegistrySnapshot(
                servers = parseServers(root[MCP_REG_FIELD_SERVERS] as? JsonObject),
                harnesses = parseHarnesses(root[MCP_REG_FIELD_HARNESSES] as? JsonArray),
            )
        }
    }

    /** Fail-closed read for mutating operations: refuses to wipe an existing malformed or unreadable registry. */
    public fun readStrict(): McpRegistrySnapshot {
        if (!Files.exists(file)) return McpRegistrySnapshot()
        val text = Files.readString(file)
        if (text.isBlank()) return McpRegistrySnapshot()
        val root = JsonScalars.objectOrNull(json, text)
            ?: throw IOException("Malformed MCP registry: $file")
        return McpRegistrySnapshot(
            servers = parseServers(root[MCP_REG_FIELD_SERVERS] as? JsonObject),
            harnesses = parseHarnesses(root[MCP_REG_FIELD_HARNESSES] as? JsonArray),
        )
    }

    private fun readTextOrNull(): String? = try {
        if (Files.isRegularFile(file)) Files.readString(file) else null
    } catch (_: IOException) {
        null
    }

    /** Canonical `name -> entry` JSON map of all user-imported servers. */
    public fun entries(): JsonObject = read().entryMap()

    /** Atomically writes [snapshot] (0600), keeping an owner-only backup when replacing an existing file. */
    public fun save(snapshot: McpRegistrySnapshot) {
        val rendered = json.encodeToString(JsonObject.serializer(), encode(snapshot)) + "\n"
        val current = try {
            if (Files.isRegularFile(file)) Files.readString(file) else null
        } catch (_: IOException) {
            null
        }
        if (current == rendered) return
        if (current != null) writeBackup(current)
        SecureFile.writeAtomic0600(file, rendered)
    }

    /** Merges [records] and optional [harnesses] into the persisted registry and returns the updated snapshot. */
    public fun merge(
        records: List<McpRegistryRecord>,
        harnesses: Set<McpHarness>? = null,
    ): McpRegistrySnapshot {
        val existing = readStrict()
        val nextServers = LinkedHashMap(existing.servers)
        records.forEach { record -> nextServers[record.name] = record }
        val nextHarnesses = harnesses?.let { existing.harnesses + it } ?: existing.harnesses
        val updated = McpRegistrySnapshot(nextServers, nextHarnesses)
        save(updated)
        return updated
    }

    /** Records [harnesses] as connected without altering stored servers. */
    public fun rememberHarnesses(harnesses: Set<McpHarness>): McpRegistrySnapshot {
        val existing = readStrict()
        val updated = existing.copy(harnesses = existing.harnesses + harnesses)
        save(updated)
        return updated
    }

    private fun parseServers(obj: JsonObject?): Map<String, McpRegistryRecord> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, McpRegistryRecord>()
        obj.forEach { (name, value) ->
            val rec = value as? JsonObject ?: return@forEach
            val entry = rec[MCP_REG_FIELD_ENTRY] as? JsonObject ?: return@forEach
            val source = JsonScalars.str(rec, MCP_REG_FIELD_SOURCE)?.let(names::source) ?: McpImportSource.MANUAL
            val importedAt = JsonScalars.str(rec, MCP_REG_FIELD_IMPORTED_AT)
            out[name] = McpRegistryRecord(name, entry, source, importedAt)
        }
        return out
    }

    private fun parseHarnesses(arr: JsonArray?): Set<McpHarness> =
        arr.orEmpty().mapNotNull(JsonScalars::str).mapNotNull(names::harness).toSet()

    private fun encode(snapshot: McpRegistrySnapshot): JsonObject = buildJsonObject {
        put(MCP_REG_FIELD_VERSION, MCP_REGISTRY_SCHEMA_VERSION)
        putJsonObject(MCP_REG_FIELD_SERVERS) {
            snapshot.servers.entries.sortedBy { it.key }.forEach { (name, record) ->
                putJsonObject(name) {
                    put(MCP_REG_FIELD_ENTRY, record.entry)
                    put(MCP_REG_FIELD_SOURCE, record.source.wire)
                    record.importedAt?.let { put(MCP_REG_FIELD_IMPORTED_AT, it) }
                }
            }
        }
        put(
            MCP_REG_FIELD_HARNESSES,
            buildJsonArray {
                snapshot.harnesses.sortedBy { it.wire }.forEach { add(JsonPrimitive(it.wire)) }
            },
        )
    }

    private fun writeBackup(previous: String) {
        val backupDir = paths.stateDir.resolve(MCP_REGISTRY_BACKUP_DIR)
        if (Files.isSymbolicLink(backupDir)) throw IOException("MCP registry backup directory must not be a link")
        SecureFile.ownerOnlyDirectory(backupDir)?.let { reason ->
            throw IOException("MCP registry backup directory is not owner-only: $reason")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(previous.toByteArray(Charsets.UTF_8))
        val hash = HexFormat.of().formatHex(digest).take(MCP_BACKUP_HASH_CHARS)
        val target = backupDir.resolve("$MCP_REGISTRY_FILE_NAME.bak-$hash")
        if (!Files.exists(target)) SecureFile.writeAtomic0600(target, previous)
    }
}
