// Direct file fallback for syncing Splice MCP servers into Codex `config.toml` when `codex app-server`
// RPC is unavailable (e.g. offline or Codex CLI not yet installed). Uses atomic 0600 writes and backups.
package splice.app.mcp

import kotlinx.serialization.json.JsonObject
import splice.core.config.StatePaths
import splice.core.mcp.MCP_BACKUP_HASH_CHARS
import splice.core.topology.TopologyFileTarget
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

private const val CODEX_BACKUP_DIR = "native-codex-config-backups"

/** Updates `[mcp_servers.<name>]` entries directly in `<codexHome>/config.toml` with owner-only backups. */
internal class CodexMcpTomlWriter(private val paths: StatePaths) {
    private val codec = CodexMcpTomlCodec()

    /** Writes [entries] (Codex-shaped JSON entries) into [configFile]. Returns true when modified. */
    fun sync(configFile: Path, entries: Map<String, JsonObject>): Boolean {
        if (entries.isEmpty()) return false
        val target = TopologyFileTarget.of(configFile)
        val existing = if (Files.isRegularFile(target)) Files.readString(target) else ""
        val updated = codec.upsert(existing, entries)
        if (updated == existing && Files.isRegularFile(target)) return false
        if (existing.isNotEmpty()) backup(existing)
        SecureFile.writeAtomic0600(target, updated)
        return true
    }

    private fun backup(previous: String) {
        val backupDir = paths.stateDir.resolve(CODEX_BACKUP_DIR)
        SecureFile.ownerOnlyDirectory(backupDir)?.let { reason ->
            throw IOException("Codex MCP backup directory is not owner-only: $reason")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(previous.toByteArray(Charsets.UTF_8))
        val hash = HexFormat.of().formatHex(digest).take(MCP_BACKUP_HASH_CHARS)
        val target = backupDir.resolve("config.toml.bak-$hash")
        if (!Files.exists(target)) SecureFile.writeAtomic0600(target, previous)
    }
}
