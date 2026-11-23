// Reads and updates Codex `[mcp_servers.<name>]` sections in `config.toml` without pulling a TOML
// dependency into `:app`. Preserves all unrelated sections, comments, and per-server restriction keys.
package splice.app.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars

private val SECTION_HEADER = Regex("^\\[([^\\[\\]]+)]\\s*$")

private const val SUBTABLE_SEGMENT_COUNT = 3
private const val FIELD_COMMAND = "command"
private const val FIELD_HEADERS = "headers"
private const val FIELD_HTTP_HEADERS = "http_headers"

private val CODEX_TRANSPORT_KEYS = setOf(
    FIELD_COMMAND,
    "args",
    "env",
    "cwd",
    "env_vars",
    "url",
    FIELD_HEADERS,
    FIELD_HTTP_HEADERS,
    "env_http_headers",
    "bearer_token_env_var",
)

/** Parses and updates `[mcp_servers.<name>]` tables in Codex `config.toml` text. */
internal class CodexMcpTomlCodec {
    private val scan = CodexMcpTomlScan()

    /** Reads enabled `[mcp_servers.<name>]` entries into canonical Claude-style MCP JSON objects. */
    fun parse(tomlText: String): Map<String, JsonObject> {
        val raw = LinkedHashMap<String, MutableServer>()
        var current: SectionTarget? = null
        scan.joinMultilineValues(tomlText.lines()).forEach { rawLine ->
            val stripped = scan.stripComment(rawLine).trim()
            if (stripped.isEmpty()) return@forEach
            val header = SECTION_HEADER.matchEntire(stripped)
            if (header != null) {
                current = parseSectionTarget(header.groupValues[1].trim())
            } else {
                current?.let { applyKeyValue(it, stripped, raw) }
            }
        }
        val out = LinkedHashMap<String, JsonObject>()
        raw.forEach { (name, srv) ->
            if (!srv.disabled) srv.toCanonicalJson()?.let { out[name] = it }
        }
        return out
    }

    /** Replaces or appends `[mcp_servers.<name>]` blocks for [entries], preserving unrelated TOML verbatim. */
    fun upsert(existing: String, entries: Map<String, JsonObject>): String {
        if (entries.isEmpty()) return existing
        val preserved = extractNonTransportLines(existing, entries.keys)
        val filtered = stripTargetSections(existing, entries.keys).trimEnd()
        val blocks = entries.entries.sortedBy { it.key }.map { (name, entry) ->
            renderServerBlock(name, entry, preserved[name].orEmpty())
        }
        return buildString {
            if (filtered.isNotEmpty()) {
                append(filtered)
                append("\n\n")
            }
            append(blocks.joinToString("\n\n"))
            append("\n")
        }
    }

    private fun extractNonTransportLines(text: String, targets: Set<String>): Map<String, List<String>> {
        val kept = LinkedHashMap<String, MutableList<String>>()
        var activeName: String? = null
        var inSubtable = false
        text.lines().forEach { line ->
            val trimmed = scan.stripComment(line).trim()
            val header = SECTION_HEADER.matchEntire(trimmed)
            val targetName = activeName
            val inTargetMain = targetName != null && !inSubtable
            if (header != null) {
                val target = parseSectionTarget(header.groupValues[1].trim())
                activeName = target?.name?.takeIf { it in targets }
                inSubtable = target?.subtable != null
            } else if (inTargetMain && '=' in trimmed) {
                val key = scan.unquoteKey(trimmed.substringBefore('=').trim())
                if (key !in CODEX_TRANSPORT_KEYS) {
                    kept.getOrPut(checkNotNull(targetName)) { mutableListOf() }.add(line.trim())
                }
            }
        }
        return kept
    }

    private fun stripTargetSections(text: String, targets: Set<String>): String {
        val out = mutableListOf<String>()
        var skipping = false
        text.lines().forEach { line ->
            val trimmed = scan.stripComment(line).trim()
            val header = SECTION_HEADER.matchEntire(trimmed)
            if (header != null) {
                val target = parseSectionTarget(header.groupValues[1].trim())
                skipping = target != null && target.name in targets
            }
            if (!skipping) out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun renderServerBlock(name: String, entry: JsonObject, preservedLines: List<String>): String = buildString {
        append("[mcp_servers.")
        append(scan.formatKey(name))
        append("]\n")
        JsonScalars.str(entry, "url")?.let { url ->
            append("url = ")
            append(scan.quoteString(url))
            append("\n")
        }
        JsonScalars.str(entry, FIELD_COMMAND)?.let { cmd ->
            append("command = ")
            append(scan.quoteString(cmd))
            append("\n")
        }
        (entry["args"] as? JsonArray)?.mapNotNull(JsonScalars::str)?.let { args ->
            append("args = [")
            append(args.joinToString(", ", transform = scan::quoteString))
            append("]\n")
        }
        JsonScalars.str(entry, "cwd")?.let { cwd ->
            append("cwd = ")
            append(scan.quoteString(cwd))
            append("\n")
        }
        renderStringMap(FIELD_HTTP_HEADERS, entry[FIELD_HTTP_HEADERS] as? JsonObject)?.let { append(it).append("\n") }
        renderStringMap("env", entry["env"] as? JsonObject)?.let { append(it).append("\n") }
        preservedLines.forEach { append(it).append("\n") }
    }.trimEnd()

    private fun renderStringMap(field: String, obj: JsonObject?): String? {
        if (obj == null || obj.isEmpty()) return null
        val pairs = obj.entries.sortedBy { it.key }.mapNotNull { (k, v) ->
            JsonScalars.str(v)?.let { "${scan.formatKey(k)} = ${scan.quoteString(it)}" }
        }
        return if (pairs.isEmpty()) null else "$field = { ${pairs.joinToString(", ")} }"
    }

    private fun applyKeyValue(target: SectionTarget, line: String, raw: MutableMap<String, MutableServer>) {
        val eq = scan.indexOfTopLevel(line, '=')
        if (eq <= 0) return
        val key = scan.unquoteKey(line.substring(0, eq).trim())
        val value = line.substring(eq + 1).trim()
        val server = raw.getOrPut(target.name) { MutableServer() }
        when (target.subtable) {
            null -> applyMainField(server, key, value)
            FIELD_HTTP_HEADERS, FIELD_HEADERS -> scan.parseScalarString(value)?.let { server.headers[key] = it }
            "env" -> scan.parseScalarString(value)?.let { server.env[key] = it }
            else -> Unit
        }
    }

    private fun applyMainField(server: MutableServer, key: String, value: String) {
        when (key) {
            "enabled" -> if (value == "false") server.disabled = true
            "url" -> server.url = scan.parseScalarString(value)
            FIELD_COMMAND -> server.command = scan.parseScalarString(value)
            "cwd" -> server.cwd = scan.parseScalarString(value)
            else -> applyStructuredField(server, key, value)
        }
    }

    private fun applyStructuredField(server: MutableServer, key: String, value: String) {
        when (key) {
            "args" -> server.args = scan.parseStringArray(value)
            FIELD_HTTP_HEADERS, FIELD_HEADERS -> server.headers.putAll(scan.parseInlineTable(value))
            "env" -> server.env.putAll(scan.parseInlineTable(value))
            else -> Unit
        }
    }

    private fun parseSectionTarget(header: String): SectionTarget? {
        val segments = scan.splitDottedPath(header)
        val valid = segments.size in 2..SUBTABLE_SEGMENT_COUNT &&
            segments[0] == "mcp_servers" &&
            segments[1].isNotBlank()
        return if (!valid) {
            null
        } else {
            SectionTarget(segments[1], segments.getOrNull(2))
        }
    }

    private data class SectionTarget(val name: String, val subtable: String?)

    private class MutableServer {
        var disabled: Boolean = false
        var url: String? = null
        var command: String? = null
        var cwd: String? = null
        var args: List<String> = emptyList()
        val headers: LinkedHashMap<String, String> = LinkedHashMap()
        val env: LinkedHashMap<String, String> = LinkedHashMap()

        fun toCanonicalJson(): JsonObject? {
            val currentUrl = url?.takeIf { it.isNotBlank() }
            val currentCommand = command?.takeIf { it.isNotBlank() }
            return when {
                currentUrl != null -> toHttpJson(currentUrl)
                currentCommand != null -> toStdioJson(currentCommand)
                else -> null
            }
        }

        private fun toHttpJson(currentUrl: String): JsonObject = buildJsonObject {
            put("type", "http")
            put("url", currentUrl)
            if (headers.isNotEmpty()) {
                putJsonObject(FIELD_HEADERS) { headers.forEach { (k, v) -> put(k, v) } }
            }
        }

        private fun toStdioJson(currentCommand: String): JsonObject = buildJsonObject {
            put(FIELD_COMMAND, currentCommand)
            if (args.isNotEmpty()) {
                put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
            }
            cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            if (env.isNotEmpty()) {
                putJsonObject("env") { env.forEach { (k, v) -> put(k, v) } }
            }
        }
    }
}
