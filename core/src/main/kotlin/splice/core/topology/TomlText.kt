// NEW: V4-128 — TOML key spelling and the JSON-to-TOML emitter the topology writer composes with.
// Split from TopologyWriter.kt by concern (concentration, 2026-09-18); that file's header states
// what the edits are and why the output is verified.
package splice.core.topology

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private const val CONTROL_LIMIT = ' '

/** Key spelling: how a TOML key compares, renders and escapes, and the canonical JSON tree. */
internal class TomlKeys {
    private val bare = Regex("[A-Za-z0-9_-]+")

    /** One layer of TOML quoting removed. ktoml hands a quoted key back WITH its quotes (see
     *  ProviderConfig.staticHeaders), so both spellings of one key must compare equal. */
    fun unquote(raw: String): String {
        val wrapped = raw.length > 1 && raw.first() in "\"'"
        val quoted = wrapped && raw.last() == raw.first()
        return if (quoted) raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\") else raw
    }

    fun render(key: String): String = if (bare.matches(key)) key else "\"${escape(key)}\""

    fun path(path: List<String>): String = path.joinToString(".", transform = ::render)

    fun escape(text: String): String = buildString {
        text.forEach { char ->
            when {
                char == '\\' -> append("\\\\")
                char == '"' -> append("\\\"")
                char == '\n' -> append("\\n")
                char == '\t' -> append("\\t")
                char < CONTROL_LIMIT -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
    }

    fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.associate { (key, value) -> unquote(key) to canonical(value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }

    fun at(tree: JsonElement?, path: List<String>): JsonElement? =
        path.fold(tree) { node, key -> (node as? JsonObject)?.get(key) }

    fun firstDifference(a: JsonElement?, b: JsonElement?, path: List<String>): List<String>? {
        if (a == b) return null
        if (a !is JsonObject || b !is JsonObject) return path
        return (a.keys + b.keys).firstNotNullOfOrNull { firstDifference(a[it], b[it], path + it) }
    }

    fun isPrefix(prefix: List<String>, path: List<String>): Boolean =
        prefix.size <= path.size && path.subList(0, prefix.size) == prefix
}

/** JSON values as TOML text. */
internal class TomlEmit(private val keys: TomlKeys) {

    fun inline(value: JsonElement): String = when (value) {
        is JsonObject -> if (value.isEmpty()) "{}" else value.entries.joinToString(", ", "{ ", " }") { pair(it) }
        is JsonArray -> array(value)
        is JsonPrimitive -> if (value.isString) "\"${keys.escape(value.content)}\"" else value.content
    }

    fun line(key: List<String>, value: JsonElement): String = "${keys.path(key)} = ${inline(value)}\n"

    /** A new table and its sub-tables, as sections separated by a blank line. A table holding only
     *  sub-tables gets no header of its own (`[projects]` above `[projects."/repo"]` says nothing). */
    fun table(path: List<String>, table: JsonObject): String {
        val (tables, values) = table.entries.partition { it.value is JsonObject }
        val own = values.isNotEmpty() || tables.isEmpty()
        val head = "[${keys.path(path)}]\n" + values.joinToString("") { line(listOf(it.key), it.value) }
        val subs = tables.map { table(path + it.key, it.value.jsonObject) }
        return (if (own) listOf(head) + subs else subs).joinToString("\n")
    }

    /** An array of tables as `[[path]]` sections, one per element. */
    fun arrayTables(path: List<String>, elements: List<JsonObject>): String = elements.joinToString("\n") { element ->
        "[[${keys.path(path)}]]\n" + element.entries.joinToString("") { line(listOf(it.key), it.value) }
    }

    private fun pair(entry: Map.Entry<String, JsonElement>): String =
        "${keys.render(entry.key)} = ${inline(entry.value)}"

    private fun array(value: JsonArray): String {
        val tables = value.any { it is JsonObject }
        return if (tables) {
            value.joinToString("", "[\n", "]") { "  ${inline(it)},\n" }
        } else {
            value.joinToString(", ", "[", "]") { inline(it) }
        }
    }
}
