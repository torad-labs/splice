// NEW: a caller's output schema rewritten into a vendor's strict subset, and the answer brought back to the caller's
// schema (2026-10-27). The subset is the vendor's fact (StrictSchemaSubset, stated in its provider module); this file
// only knows JSON Schema.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.dialect.responses.StrictSchemaSubset

private const val TYPE = "type"
private const val PROPERTIES = "properties"
private const val REQUIRED = "required"
private const val ADDITIONAL = "additionalProperties"
private const val ITEMS = "items"
private const val ENUM = "enum"
private const val CONST = "const"
private const val ANY_OF = "anyOf"
private const val REF = "\$ref"
private const val DEFS = "\$defs"
private const val DEFINITIONS = "definitions"
private const val NULL = "null"
private const val OBJECT = "object"
private const val ARRAY = "array"

internal object ResponsesStrictSchema {
    /** JSON Schema annotations: they describe a value without constraining it, so dropping one keeps its meaning. */
    private val ANNOTATIONS = setOf(
        "\$schema",
        "\$id",
        "\$comment",
        "default",
        "examples",
        "deprecated",
        "readOnly",
        "writeOnly",
    )

    /** Schema keys whose value is a list of schemas. */
    private val UNIONS = setOf(ANY_OF, "oneOf", "allOf")

    /** [schema] rewritten into [subset], and whether the rewrite made a field the caller left optional nullable. */
    data class Rewritten(val schema: JsonObject, val nullable: Boolean)

    /** [schema] rewritten into [subset]: objects closed and every key required where the subset asks, each optional
     *  field made nullable, annotations the subset does not take dropped. Null when no rewrite keeps every answer to
     *  it valid against [schema]: a keyword or format outside the subset, a reference it cannot follow, a free-form
     *  object, or a size past its limits. */
    fun rewrite(schema: JsonObject, subset: StrictSchemaSubset): Rewritten? {
        val walk = Rewrite(schema, subset)
        val rewritten = walk.root() ?: return null
        return Rewritten(rewritten, walk.nullable).takeIf { walk.withinLimits() }
    }

    /** [value], an answer to a rewritten schema, brought back to [schema], the caller's: a field the caller left
     *  optional that the answer set to null is left out. */
    fun restore(value: JsonElement, schema: JsonObject): JsonElement = Restore(schema).of(value, schema)

    private fun types(schema: JsonObject): List<String> = when (val type = schema[TYPE]) {
        is JsonPrimitive -> listOfNotNull(type.takeIf { it.isString }?.content)
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonObject, null -> emptyList()
    }

    private fun names(schema: JsonObject, key: String): List<String> =
        (schema[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun ref(schema: JsonObject): String? = (schema[REF] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The schema a reference names inside [root]: `#`, `#/$defs/<name>` or `#/definitions/<name>`. */
    private fun target(root: JsonObject, ref: String): JsonObject? = when {
        ref == "#" -> root
        ref.startsWith("#/$DEFS/") -> (root[DEFS] as? JsonObject)?.get(ref.removePrefix("#/$DEFS/")) as? JsonObject
        ref.startsWith("#/$DEFINITIONS/") ->
            (root[DEFINITIONS] as? JsonObject)?.get(ref.removePrefix("#/$DEFINITIONS/")) as? JsonObject
        else -> null
    }

    /** An object schema, by its type or by the properties it describes. */
    private fun objectish(schema: JsonObject): Boolean = OBJECT in types(schema) || PROPERTIES in schema

    private fun nonEmpty(schema: JsonObject): Boolean =
        (listOf(ENUM) + UNIONS).all { key -> (schema[key] as? JsonArray)?.isNotEmpty() ?: (key !in schema) }

    private fun acceptsNull(schema: JsonObject): Boolean =
        NULL in types(schema) || (schema[ENUM] as? JsonArray)?.contains(JsonNull) == true ||
            schema[CONST] == JsonNull ||
            UNIONS.any { key -> (schema[key] as? JsonArray).orEmpty().any { it is JsonObject && acceptsNull(it) } }

    private fun reaches(edges: Map<String, List<String>>, start: String, target: String): Boolean {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(edges[start].orEmpty())
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next == target) return true
            if (seen.add(next)) pending.addAll(edges[next].orEmpty())
        }
        return false
    }

    /** Every reference in [schema], definitions aside. A worklist: the caller chooses how deep a schema nests. */
    private fun references(schema: JsonElement): List<String> = buildList {
        val pending = ArrayDeque<JsonElement>().apply { add(schema) }
        while (pending.isNotEmpty()) {
            when (val next = pending.removeFirst()) {
                is JsonObject -> {
                    ref(next)?.let(::add)
                    next.filterKeys { it != DEFS && it != DEFINITIONS }.values.forEach(pending::add)
                }
                is JsonArray -> pending.addAll(next)
                is JsonPrimitive -> Unit
            }
        }
    }

    /** Whether [schema] names no format, or one [formats] holds; null holds every format. */
    private fun formatHeld(schema: JsonObject, formats: Set<String>?): Boolean {
        val format = (schema["format"] as? JsonPrimitive)?.content
        return format == null || formats == null || format in formats
    }

    /** Whether [schema] makes no reference, or one that names a schema inside [root]. */
    private fun referenced(root: JsonObject, schema: JsonObject): Boolean =
        ref(schema)?.let { target(root, it) != null } ?: (REF !in schema)

    /** One walk of the caller's schema, counting what the subset limits. */
    private class Rewrite(private val root: JsonObject, private val subset: StrictSchemaSubset) {
        var nullable = false
            private set
        private var properties = 0
        private var enumValues = 0
        private var chars = 0

        fun root(): JsonObject? {
            val objectRoot = types(root) == listOf(OBJECT) && UNIONS.none { it in root }
            if (subset.objectRoot && !objectRoot) return null
            if (!subset.recursion && circular()) return null
            return node(root, level = 1)
        }

        fun withinLimits(): Boolean {
            val limits = subset.limits
            return listOf(properties to limits.properties, enumValues to limits.enumValues, chars to limits.stringChars)
                .all { (count, limit) -> limit == null || count <= limit }
        }

        private fun node(source: JsonElement?, level: Int): JsonObject? {
            val schema = (source as? JsonObject)?.takeIf { admitted(it, level) } ?: return null
            count(schema)
            val inner = if (objectish(schema)) level + 1 else level
            val out = linkedMapOf<String, JsonElement>()
            for ((key, value) in schema) {
                if (key !in subset.keywords) continue
                out[key] = child(key, value, inner) ?: return null
            }
            return if (objectish(schema)) closed(schema, out) else JsonObject(out)
        }

        /** Whether every keyword, format and reference in [schema] lies inside the subset at [level]. */
        private fun admitted(schema: JsonObject, level: Int): Boolean {
            val keywords = schema.keys.all { it in subset.keywords || it in ANNOTATIONS }
            val levels = subset.limits.nestingLevels
            val nested = !objectish(schema) || levels == null || level <= levels
            return keywords && nested && formatHeld(schema, subset.formats) && referenced(root, schema) &&
                nonEmpty(schema)
        }

        private fun child(key: String, value: JsonElement, level: Int): JsonElement? = when (key) {
            PROPERTIES -> mapped(value as? JsonObject, level)
            ITEMS -> node(value, level)
            DEFS, DEFINITIONS -> mapped(value as? JsonObject, level = 1)
            ADDITIONAL -> value.takeIf { (it as? JsonPrimitive)?.booleanOrNull != null }
            in UNIONS -> listed(value as? JsonArray, level)
            else -> value
        }

        private fun mapped(schemas: JsonObject?, level: Int): JsonObject? {
            if (schemas == null) return null
            val out = linkedMapOf<String, JsonElement>()
            for ((name, schema) in schemas) {
                chars += name.length
                out[name] = node(schema, level) ?: return null
            }
            return JsonObject(out)
        }

        private fun listed(branches: JsonArray?, level: Int): JsonArray? {
            if (branches == null) return null
            val out = mutableListOf<JsonElement>()
            for (branch in branches) out += node(branch, level) ?: return null
            return JsonArray(out)
        }

        private fun count(schema: JsonObject) {
            val values = (schema[ENUM] as? JsonArray).orEmpty() + listOfNotNull(schema[CONST])
            enumValues += (schema[ENUM] as? JsonArray)?.size ?: 0
            chars += values.sumOf { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.length ?: 0 }
            properties += (schema[PROPERTIES] as? JsonObject)?.size ?: 0
        }

        /** An object schema closed and every key required, as the subset asks. A free-form object, or one that
         *  requires a key it does not describe, has no closed form an answer to it could take. */
        private fun closed(source: JsonObject, out: MutableMap<String, JsonElement>): JsonObject? {
            val props = out[PROPERTIES] as? JsonObject ?: return null
            val required = names(source, REQUIRED)
            if (props.isEmpty() || !props.keys.containsAll(required)) return null
            if (subset.closedObjects) out[ADDITIONAL] = JsonPrimitive(false)
            if (subset.everyKeyRequired) {
                out[PROPERTIES] = JsonObject(props.mapValues { (name, schema) -> optional(name in required, schema) })
                out[REQUIRED] = JsonArray(props.keys.map(::JsonPrimitive))
            }
            return JsonObject(out)
        }

        private fun optional(required: Boolean, schema: JsonElement): JsonElement {
            val node = schema as? JsonObject ?: return schema
            if (required || acceptsNull(node)) return node
            nullable = true
            val types = types(node)
            val scalar = types.isNotEmpty() && OBJECT !in types && ARRAY !in types && ENUM !in node && CONST !in node
            return if (scalar) {
                JsonObject(node + (TYPE to JsonArray((types + NULL).map(::JsonPrimitive))))
            } else {
                JsonObject(mapOf(ANY_OF to JsonArray(listOf(node, JsonObject(mapOf(TYPE to JsonPrimitive(NULL)))))))
            }
        }

        /** Whether a reference reaches, through further references, the schema it sits in. */
        private fun circular(): Boolean {
            val defs = ((root[DEFS] as? JsonObject).orEmpty() + (root[DEFINITIONS] as? JsonObject).orEmpty())
            if ("#" in references(root)) return true
            val edges = defs.mapValues { (_, schema) -> references(schema).map { it.substringAfterLast('/') } }
            return defs.keys.any { start -> reaches(edges, start, start) }
        }
    }

    /** An answer walked alongside the caller's schema, leaving out each null its optional fields came back as. */
    private class Restore(private val root: JsonObject) {
        fun of(value: JsonElement, schema: JsonObject): JsonElement {
            val node = resolved(schema)
            return when (value) {
                is JsonObject -> objectOf(value, node)
                is JsonArray -> (branches(node).firstNotNullOfOrNull { it[ITEMS] as? JsonObject })
                    ?.let { items -> JsonArray(value.map { of(it, items) }) } ?: value
                is JsonPrimitive -> value
            }
        }

        private fun objectOf(value: JsonObject, schema: JsonObject): JsonObject {
            val shape = branches(schema).firstOrNull { branch ->
                val props = (branch[PROPERTIES] as? JsonObject)?.keys ?: return@firstOrNull false
                props.containsAll(value.keys)
            } ?: return value
            val props = shape[PROPERTIES] as JsonObject
            val required = names(shape, REQUIRED).toSet()
            return JsonObject(
                value.filterNot { (name, field) -> field is JsonNull && name !in required }
                    .mapValues { (name, field) -> (props[name] as? JsonObject)?.let { of(field, it) } ?: field },
            )
        }

        /** [schema] and the union branches it may stand for, references followed. */
        private fun branches(schema: JsonObject): List<JsonObject> =
            listOf(schema) + UNIONS.flatMap { key ->
                (schema[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::resolved) }
            }

        /** [schema] with its reference chain followed, as far as the chain stays new. */
        private fun resolved(schema: JsonObject): JsonObject {
            var node = schema
            val seen = mutableSetOf<String>()
            var ref = ref(node)
            while (ref != null && seen.add(ref)) {
                node = target(root, ref) ?: return node
                ref = ref(node)
            }
            return node
        }
    }
}
