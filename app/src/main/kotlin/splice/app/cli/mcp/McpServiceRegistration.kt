package splice.app.cli.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.TempleMcpEntry
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

// why: a SHA-256 prefix gives prior registration backups a compact content-derived name.
private const val BACKUP_HASH_CHARS = 12

internal class McpServiceRegistration(private val env: EnvReader) {
    fun persist(source: TempleMcpEntry, wanted: McpServiceRequestParser.Request) {
        val previous = source.registration()
        val body = body(previous, wanted)
        if (previous == body) return
        val file = source.registrationFile
        Files.createDirectories(file.parent)
        if (Files.exists(file)) backup(file)
        SecureFile.writeAtomic0600(file, body.toString())
    }

    private fun body(
        previous: JsonObject,
        wanted: McpServiceRequestParser.Request,
    ): JsonObject {
        val previousServer = previous["server"] as? JsonObject
        return buildJsonObject {
            previous.filterKeys { it !in setOf("server", "codex_home") }
                .forEach { (key, value) -> put(key, value) }
            put("server", server(previousServer, wanted))
            nativeHome(previous, wanted)?.let { put("codex_home", it) }
        }
    }

    private fun nativeHome(previous: JsonObject, wanted: McpServiceRequestParser.Request): String? =
        wanted.codexHome?.toString() ?: JsonScalars.str(previous, "codex_home")
            ?: env("TEMPLE_CODEX_HOME") ?: env("CODEX_HOME")

    private fun server(previous: JsonObject?, wanted: McpServiceRequestParser.Request): JsonObject = buildJsonObject {
        previous?.filterKeys { it !in setOf("command", "args", "cwd", "env") }
            ?.forEach { (key, value) -> put(key, value) }
        put("command", wanted.command)
        put("args", JsonArray(wanted.args.map { JsonPrimitive(it) }))
        wanted.cwd?.let { put("cwd", it) }
        put("env", environment(previous, wanted))
    }

    private fun environment(
        previous: JsonObject?,
        wanted: McpServiceRequestParser.Request,
    ): JsonObject = buildJsonObject {
        (previous?.get("env") as? JsonObject)?.forEach { (key, value) -> put(key, value) }
        wanted.env.forEach { (key, value) -> put(key, value) }
        put("TEMPLE_URL", wanted.url)
    }

    private fun backup(file: Path) {
        val old = Files.readString(file)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(old.toByteArray()))
            .take(BACKUP_HASH_CHARS)
        val saved = file.resolveSibling("${file.fileName}.bak-$hash")
        if (!Files.exists(saved)) SecureFile.writeAtomic0600(saved, old)
    }
}
