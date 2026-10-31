package splice.app.cli.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.PathProbe
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

private const val INVALID_LAUNCH_DESCRIPTOR =
    "Temple MCP launch descriptor is invalid; verify command, args, cwd, and env."

/** The string constraints ProcessBuilder applies after a descriptor would otherwise be durable. */
internal class McpProcessStrings {
    fun valid(command: String, args: List<String>, environment: Map<String, String>): Boolean =
        command.isNotBlank() && validValue(command) && args.all(::validValue) && environment.all { (name, value) ->
            name.isNotEmpty() && '=' !in name && validValue(name) && validValue(value)
        }

    fun validValue(value: String): Boolean = '\u0000' !in value
}

internal class McpServiceRequestParser {
    private val process = McpProcessStrings()
    internal data class Request(
        val command: String,
        val args: List<String>,
        val cwd: String?,
        val env: Map<String, String>,
        val url: String,
        val codexHome: Path?,
    )

    internal data class ParseResult(val request: Request?, val error: String?)

    fun parse(args: List<String>): ParseResult {
        if (args.take(2) != listOf("install", "temple")) return ParseResult(null, null)
        val pairs = args.drop(2).chunked(2)
        val flags = pairs.filter { it.size == 2 }.associate { it[0] to it[1] }
        val allowed = setOf("--script", "--launch-file", "--url", "--command", "--codex-home")
        val malformed = pairs.any { it.size != 2 } || flags.size != pairs.size
        return if (malformed || !allowed.containsAll(flags.keys)) {
            ParseResult(null, null)
        } else {
            requested(flags)
        }
    }

    private fun requested(flags: Map<String, String>): ParseResult {
        val scriptMode = "--script" in flags
        val launchMode = "--launch-file" in flags
        val url = flags["--url"]?.takeIf(::validUrl)
        val codexHome = flags["--codex-home"]?.let(::path)
        val validShape = scriptMode != launchMode &&
            !(launchMode && "--command" in flags) &&
            url != null && process.validValue(url) &&
            !("--codex-home" in flags && codexHome == null)
        if (!validShape) return ParseResult(null, null)
        return if (scriptMode) {
            scriptRequest(flags, checkNotNull(url), codexHome)
        } else {
            launchRequest(flags, checkNotNull(url), codexHome)
        }
    }

    private fun scriptRequest(flags: Map<String, String>, url: String, codexHome: Path?): ParseResult {
        val script = flags["--script"]?.let(::path)?.takeIf { Files.isRegularFile(it) }
        val command = flags["--command"] ?: "bun"
        val request = if (script != null && process.valid(command, listOf(script.toString()), emptyMap())) {
            Request(command, listOf(script.toString()), null, emptyMap(), url, codexHome)
        } else {
            null
        }
        return ParseResult(request, null)
    }

    private fun launchRequest(flags: Map<String, String>, url: String, codexHome: Path?): ParseResult {
        val file = flags["--launch-file"]?.let(::path)
        val launch = file?.let(::launch)
        val request = launch?.let { Request(it.command, it.args, it.cwd, it.env, url, codexHome) }
        return if (request == null) ParseResult(null, INVALID_LAUNCH_DESCRIPTOR) else ParseResult(request, null)
    }

    private data class Launch(
        val command: String,
        val args: List<String>,
        val cwd: String?,
        val env: Map<String, String>,
    )

    private data class ParsedDirectory(val value: String?, val valid: Boolean)

    private fun launch(file: Path): Launch? = descriptor(file)?.let(::launch)

    private fun descriptor(file: Path): JsonObject? = if (Files.isRegularFile(file)) {
        try {
            Json.parseToJsonElement(Files.readString(file)) as? JsonObject
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    } else {
        null
    }

    private fun launch(root: JsonObject): Launch? {
        val command = root["command"]?.let(::text)?.takeIf(String::isNotBlank)
        val argArray = root["args"] as? JsonArray
        val args = argArray?.mapNotNull(::text)
        val directory = directory(root)
        val environment = environment(root)
        val valid = command != null &&
            argArray != null &&
            args?.size == argArray.size &&
            directory.valid &&
            environment != null &&
            process.valid(
                command,
                checkNotNull(args),
                checkNotNull(environment),
            )
        return if (valid) {
            Launch(checkNotNull(command), checkNotNull(args), directory.value, checkNotNull(environment))
        } else {
            null
        }
    }

    private fun directory(root: JsonObject): ParsedDirectory {
        val raw = text(root["cwd"])
        val path = raw?.takeIf(String::isNotBlank)?.let(::path)
        val exists = path?.let { Files.isDirectory(it) } == true
        return if ("cwd" !in root) {
            ParsedDirectory(null, true)
        } else {
            ParsedDirectory(
                path?.takeIf { exists }?.toString(),
                exists,
            )
        }
    }

    private fun environment(root: JsonObject): Map<String, String>? =
        if ("env" in root) stringMap(root["env"]) else emptyMap()

    private fun path(raw: String): Path? = PathProbe.spelled(raw)?.toAbsolutePath()?.normalize()

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun stringMap(element: JsonElement?): Map<String, String>? {
        val obj = element as? JsonObject ?: return null
        val entries = obj.mapNotNull { (key, value) -> text(value)?.let { key to it } }
        return entries.takeIf { it.size == obj.size }?.toMap()
    }

    private fun validUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: java.net.URISyntaxException) {
        false
    }
}
