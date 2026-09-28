// NEW: V4-388 — codex-rs code-mode-protocol/src/json_schema_types.rs, ported rule for rule: the
// TypeScript a code_mode_only model reads for each nested tool's arguments in the exec manual.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import java.util.HexFormat

/** JSON Schema to the TypeScript type codex renders; a rendering past [MAX_RENDERED_SCHEMA_BYTES]
 *  becomes `unknown`, exactly as codex bounds it. */
internal object CodeModeSchemaTypes {
    fun render(schema: JsonElement): String {
        val rendered = SchemaTypeRenderer(schema).render(schema)
        return if (SchemaText.bytes(rendered) > MAX_RENDERED_SCHEMA_BYTES) UNKNOWN else rendered
    }
}

/** One render pass with codex's three budgets: per-path ref depth, total ref expansions, work bytes. */
internal class SchemaTypeRenderer(private val root: JsonElement) {
    private val budget = RenderBudget()
    private val objects = SchemaObjectRenderer(this, budget)
    private var nestedResourceDepth = 0
    private val activeRefExpansions = HashMap<String, Int>()
    private var remainingRefExpansions = MAX_TOTAL_LOCAL_REF_EXPANSIONS

    fun render(schema: JsonElement): String {
        if (budget.exhausted) return UNKNOWN
        // A nested `$id` starts a new schema resource; fragment refs below it are not resolved.
        val entersResource = schema !== root && (schema as? JsonObject)?.containsKey("\$id") == true
        if (entersResource) nestedResourceDepth += 1
        val rendered = when {
            schema is JsonObject -> renderMap(schema)
            SchemaText.flag(schema) == false -> "never"
            else -> UNKNOWN
        }
        if (entersResource) nestedResourceDepth -= 1
        return if (budget.consume(SchemaText.bytes(rendered))) rendered else UNKNOWN
    }

    fun renderMap(map: JsonObject): String = when {
        budget.exhausted -> UNKNOWN
        map.containsKey("\$ref") -> renderRef(map)
        else -> renderLiterals(map) ?: renderCombinators(map) ?: renderTyped(map) ?: renderShape(map)
    }

    private fun renderLiterals(map: JsonObject): String? = map["const"]?.let(::renderLiteral) ?: renderEnum(map)

    private fun renderCombinators(map: JsonObject): String? =
        renderVariants(map["anyOf"], " | ", parenthesize = false)
            ?: renderVariants(map["oneOf"], " | ", parenthesize = false)
            ?: renderVariants(map["allOf"], " & ", parenthesize = true)

    private fun renderEnum(map: JsonObject): String? {
        val values = map["enum"] as? JsonArray ?: return null
        val rendered = ArrayList<String>(values.size)
        for (value in values) {
            val literal = renderLiteral(value)
            if (budget.exhausted || !budget.consume(SchemaText.bytes(literal))) return UNKNOWN
            rendered += literal
        }
        return rendered.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    }

    private fun renderVariants(element: JsonElement?, separator: String, parenthesize: Boolean): String? {
        val variants = element as? JsonArray ?: return null
        val rendered = ArrayList<String>(variants.size)
        for (variant in variants) {
            if (budget.exhausted) return UNKNOWN
            val type = render(variant)
            rendered += if (parenthesize && type.contains(" | ")) "($type)" else type
        }
        return rendered.takeIf { it.isNotEmpty() }?.joinToString(separator)
    }

    private fun renderTyped(map: JsonObject): String? = when (val type = map["type"]) {
        is JsonArray -> {
            val rendered = ArrayList<String>(type.size)
            for (name in type.mapNotNull(SchemaText::string)) {
                if (budget.exhausted) return UNKNOWN
                rendered += renderTypeKeyword(map, name)
            }
            rendered.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        }
        else -> SchemaText.string(type)?.let { renderTypeKeyword(map, it) }
    }

    private fun renderShape(map: JsonObject): String = when {
        OBJECT_KEYS.any(map::containsKey) -> objects.render(map)
        map.containsKey("items") || map.containsKey("prefixItems") -> renderArray(map)
        else -> UNKNOWN
    }

    private fun renderRef(map: JsonObject): String {
        val referenced = resolveRef(map) ?: UNKNOWN
        val siblings = JsonObject(map.filterKeys { it !in REF_KEYS })
        return when {
            budget.exhausted -> UNKNOWN
            !SchemaText.hasRenderableKeywords(siblings) -> referenced
            else -> {
                val sibling = renderMap(siblings)
                when {
                    referenced == UNKNOWN -> sibling
                    sibling == UNKNOWN -> referenced
                    else -> "($referenced) & ($sibling)"
                }
            }
        }
    }

    private fun resolveRef(map: JsonObject): String? {
        val pointer = SchemaText.string(map["\$ref"])?.takeIf { nestedResourceDepth == 0 }
            ?.let(SchemaText::localJsonPointer) ?: return null
        val active = activeRefExpansions[pointer] ?: 0
        return when {
            active >= MAX_LOCAL_REF_EXPANSIONS_PER_PATH || remainingRefExpansions == 0 -> UNKNOWN
            else -> SchemaText.resolve(root, pointer)?.let { target -> expand(pointer, active, target) }
        }
    }

    private fun expand(pointer: String, active: Int, target: JsonElement): String {
        remainingRefExpansions -= 1
        activeRefExpansions[pointer] = active + 1
        return render(target).also {
            if (active == 0) activeRefExpansions.remove(pointer) else activeRefExpansions[pointer] = active
        }
    }

    private fun renderTypeKeyword(map: JsonObject, type: String): String = when (type) {
        "string" -> "string"
        "number", "integer" -> "number"
        "boolean" -> "boolean"
        "null" -> "null"
        "array" -> renderArray(map)
        "object" -> objects.render(map)
        else -> UNKNOWN
    }

    private fun renderArray(map: JsonObject): String {
        map["items"]?.let { items ->
            val item = render(items)
            return if (budget.exhausted) UNKNOWN else "Array<$item>"
        }
        val prefix = map["prefixItems"] as? JsonArray
        val types = ArrayList<String>()
        for (item in prefix.orEmpty()) {
            if (budget.exhausted) return UNKNOWN
            types += render(item)
        }
        return if (types.isEmpty()) "unknown[]" else types.joinToString(", ", "[", "]")
    }

    private fun renderLiteral(value: JsonElement): String =
        if (SchemaText.literalUpperBound(value) > budget.remaining) {
            budget.exhaust()
            UNKNOWN
        } else {
            value.toString()
        }
}

/** codex's object rendering: sorted properties, `?` for the optional, descriptions as `//` comments. */
internal class SchemaObjectRenderer(private val types: SchemaTypeRenderer, private val budget: RenderBudget) {
    fun render(map: JsonObject): String {
        val required = (map["required"] as? JsonArray).orEmpty().mapNotNull(SchemaText::string)
        val properties = map["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val sorted = properties.entries.sortedBy { it.key }
        return if (sorted.any { SchemaText.hasPropertyDescription(it.value) }) {
            described(map, properties, sorted, required)
        } else {
            inline(map, properties, sorted, required)
        }
    }

    private fun described(
        map: JsonObject,
        properties: JsonObject,
        sorted: List<Map.Entry<String, JsonElement>>,
        required: List<String>,
    ): String {
        val lines = ArrayList<String>()
        val complete = budget.pushLine(lines, "{") &&
            sorted.all { (name, value) -> pushDescribed(lines, name, value, required) } &&
            additionalProperties(lines, map, properties, "  ") &&
            budget.pushLine(lines, "}")
        return if (complete) lines.joinToString("\n") else UNKNOWN
    }

    private fun pushDescribed(
        lines: MutableList<String>,
        name: String,
        value: JsonElement,
        required: List<String>,
    ): Boolean {
        val description = SchemaText.string((value as? JsonObject)?.get("description")).orEmpty()
        val commented = description.lines().map(String::trim).filter(String::isNotEmpty).all { line ->
            SchemaText.bytes(line) + COMMENT_PREFIX_BYTES <= budget.remaining && budget.pushLine(lines, "  // $line")
        }
        if (!commented) return false
        val property = property(name, value, required)
        return !budget.exhausted && budget.pushLine(lines, "  $property")
    }

    private fun inline(
        map: JsonObject,
        properties: JsonObject,
        sorted: List<Map.Entry<String, JsonElement>>,
        required: List<String>,
    ): String {
        val lines = ArrayList<String>()
        val complete = sorted.all { (name, value) ->
            val property = property(name, value, required)
            !budget.exhausted && budget.pushLine(lines, property)
        } && additionalProperties(lines, map, properties, "")
        return when {
            !complete -> UNKNOWN
            lines.isEmpty() -> "{}"
            else -> "{ ${lines.joinToString(" ")} }"
        }
    }

    private fun additionalProperties(
        lines: MutableList<String>,
        map: JsonObject,
        properties: JsonObject,
        prefix: String,
    ): Boolean {
        val additional = map["additionalProperties"]
        val type = when {
            additional == null -> if (properties.isEmpty()) UNKNOWN else null
            else -> when (SchemaText.flag(additional)) {
                true -> UNKNOWN
                false -> null
                null -> types.render(additional)
            }
        }
        return type == null || budget.pushLine(lines, "$prefix[key: string]: $type;")
    }

    private fun property(name: String, value: JsonElement, required: List<String>): String {
        if (SchemaText.bytes(name) > budget.remaining) {
            budget.exhaust()
            return UNKNOWN
        }
        val optional = if (name in required) "" else "?"
        val type = types.render(value)
        return if (budget.exhausted) UNKNOWN else "${SchemaText.propertyName(name)}$optional: $type;"
    }
}

/** Intermediate strings are charged as they are built, so repeated refs cannot allocate unbounded copies. */
internal class RenderBudget {
    var remaining: Int = MAX_RENDER_WORK_BYTES
        private set
    var exhausted: Boolean = false
        private set

    fun exhaust() {
        exhausted = true
    }

    fun consume(bytes: Int): Boolean {
        if (bytes > remaining) {
            exhausted = true
            return false
        }
        remaining -= bytes
        return true
    }

    fun pushLine(lines: MutableList<String>, line: String): Boolean =
        consume(SchemaText.bytes(line)).also { if (it) lines += line }
}

/** The renderer's pure helpers: JSON scalars, JSON pointers, literal bounds, property names. */
internal object SchemaText {
    fun bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    /** A JSON string's text; a number, boolean or null is not one. */
    fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.let { JsonScalars.str(it) }

    /** A JSON boolean; the string "true" is not one. */
    fun flag(element: JsonElement?): Boolean? =
        (element as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.let { JsonScalars.str(it) }
            ?.toBooleanStrictOrNull()

    fun hasRenderableKeywords(map: JsonObject): Boolean = RENDERABLE_KEYWORDS.any(map::containsKey)

    fun hasPropertyDescription(value: JsonElement): Boolean =
        string((value as? JsonObject)?.get("description"))?.isNotEmpty() == true

    fun propertyName(name: String): String =
        if (CodeModeManual.identifier(name) == name) name else JsonPrimitive(name).toString()

    /** `#` plus a percent-decoded JSON pointer, or null for anything that is not a local fragment. */
    fun localJsonPointer(reference: String): String? {
        if (!reference.startsWith("#")) return null
        val pointer = percentDecode(reference.substring(1)) ?: return null
        return pointer.takeIf { it.isEmpty() || it.startsWith("/") }
    }

    /** RFC 6901, as serde_json's Value::pointer reads it (array indexes without `+` or leading zeros). */
    fun resolve(root: JsonElement, pointer: String): JsonElement? {
        if (pointer.isEmpty()) return root
        return pointer.substring(1).split("/").fold<String, JsonElement?>(root) { node, raw ->
            val token = raw.replace("~1", "/").replace("~0", "~")
            when (node) {
                is JsonObject -> node[token]
                is JsonArray -> arrayIndex(token)?.let(node::getOrNull)
                else -> null
            }
        }
    }

    fun literalUpperBound(value: JsonElement): Int = when (value) {
        is JsonObject -> value.entries.fold(2) { size, (key, item) ->
            size + OBJECT_ENTRY_BYTES + bytes(key) * ESCAPE_WIDTH + literalUpperBound(item)
        }
        is JsonArray -> value.fold(2) { size, item -> size + 1 + literalUpperBound(item) }
        is JsonPrimitive -> if (value.isString) bytes(value.content) * ESCAPE_WIDTH + 2 else value.content.length
    }

    private fun arrayIndex(token: String): Int? =
        token.takeUnless { it.startsWith("+") || (it.startsWith("0") && it.length != 1) }?.toIntOrNull()

    private fun percentDecode(fragment: String): String? {
        val source = fragment.toByteArray(Charsets.UTF_8)
        // One char per byte, so a byte index reads the same hex digits here (codex decodes the bytes).
        val byteChars = String(source, Charsets.ISO_8859_1)
        val decoded = java.io.ByteArrayOutputStream(source.size)
        var index = 0
        while (index < source.size) {
            if (source[index] == '%'.code.toByte()) {
                if (!isHexPair(source, index + 1)) return null
                decoded.write(HexFormat.fromHexDigits(byteChars, index + 1, index + PERCENT_TRIPLET))
                index += PERCENT_TRIPLET
            } else {
                decoded.write(source[index].toInt())
                index += 1
            }
        }
        val bytes = decoded.toByteArray()
        val text = String(bytes, Charsets.UTF_8)
        return text.takeIf { it.toByteArray(Charsets.UTF_8).contentEquals(bytes) }
    }

    /** Two ASCII hex digits at [at]; any other byte (or the end) makes the fragment undecodable. */
    private fun isHexPair(source: ByteArray, at: Int): Boolean =
        (at until at + 2).all { position ->
            source.getOrNull(position)?.let { byte -> byte >= 0 && HexFormat.isHexDigit(byte.toInt()) } == true
        }
}

private const val UNKNOWN = "unknown"

// Expose one nested recursive shape, then fall back to `unknown` on the next occurrence.
private const val MAX_LOCAL_REF_EXPANSIONS_PER_PATH = 2

// Bound repeated refs and DAG fan-out separately from cycle depth.
private const val MAX_TOTAL_LOCAL_REF_EXPANSIONS = 32

// why: codex's MAX_RENDERED_SCHEMA_BYTES — one tool's declaration past this is `unknown`, not a wall of text.
private const val MAX_RENDERED_SCHEMA_BYTES = 16_000
private const val MAX_RENDER_WORK_BYTES = MAX_RENDERED_SCHEMA_BYTES * 4

// why: codex charges `description_line.len() + 5` before pushing "  // " and the line.
private const val COMMENT_PREFIX_BYTES = 5

// why: codex's literal bound charges 4 bytes per object entry (quotes, colon, comma) beside its key and value.
private const val OBJECT_ENTRY_BYTES = 4

// why: JSON escaping widens one UTF-8 byte to at most a six-byte \uXXXX escape.
private const val ESCAPE_WIDTH = 6

// why: a percent escape is `%` plus two hex digits.
private const val PERCENT_TRIPLET = 3
private val OBJECT_KEYS = listOf("properties", "additionalProperties", "required")
private val REF_KEYS = setOf("\$ref", "\$defs", "definitions")
private val RENDERABLE_KEYWORDS = listOf(
    "const", "enum", "anyOf", "oneOf", "allOf", "type", "properties", "additionalProperties", "required", "items",
    "prefixItems",
)
