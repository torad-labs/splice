package splice.app.cli.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.TEMPLE_MCP_PROVENANCE_KEY
import splice.client.mcp.TEMPLE_MCP_REGISTERED_SERVICE
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
private const val SERVER_KEY = "server"

internal class McpServiceRegistration(private val env: EnvReader) {
    private val process = McpProcessStrings()

    fun persist(source: TempleMcpEntry, wanted: McpServiceRequestParser.Request) {
        val previous = source.registration()
        val body = body(previous, wanted)
        if (!valid(body)) throw java.io.IOException("Temple MCP registration has invalid process strings")
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
        val previousServer = previous[SERVER_KEY] as? JsonObject
        return buildJsonObject {
            previous.filterKeys { it !in setOf(SERVER_KEY, "codex_home", TEMPLE_MCP_PROVENANCE_KEY) }
                .forEach { (key, value) -> put(key, value) }
            put(SERVER_KEY, server(previousServer, wanted))
            put(TEMPLE_MCP_PROVENANCE_KEY, TEMPLE_MCP_REGISTERED_SERVICE)
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

    private fun valid(body: JsonObject): Boolean =
        (body[SERVER_KEY] as? JsonObject)?.let(::fields)?.let {
            process.valid(it.command, it.args, it.environment)
        } == true

    private fun fields(server: JsonObject): ProcessFields? {
        val command = JsonScalars.str(server, "command")
        val args = strings(server["args"])
        val environment = environment(server["env"])
        return when {
            command == null -> null
            args == null -> null
            environment == null -> null
            else -> ProcessFields(command, args, environment)
        }
    }

    private fun strings(value: JsonElement?): List<String>? {
        val values = value as? JsonArray ?: return null
        return values.mapNotNull(JsonScalars::str).takeIf { it.size == values.size }
    }

    private fun environment(value: JsonElement?): Map<String, String>? {
        val values = value as? JsonObject ?: return null
        return values.mapNotNull { (key, entry) ->
            JsonScalars.str(entry)?.let { key to it }
        }.takeIf { it.size == values.size }?.toMap()
    }

    private data class ProcessFields(
        val command: String,
        val args: List<String>,
        val environment: Map<String, String>,
    )

    private fun backup(file: Path) {
        val old = Files.readString(file)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(old.toByteArray()))
            .take(BACKUP_HASH_CHARS)
        val saved = file.resolveSibling("${file.fileName}.bak-$hash")
        if (!Files.exists(saved)) SecureFile.writeAtomic0600(saved, old)
    }
}
