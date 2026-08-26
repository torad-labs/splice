// PORT-OF: codex-rs tools/src/json_schema.rs @ 63fe5a6 (parse_tool_input_schema) + the
// parse_mcp_tool properties pre-step (tools/src/mcp_tool.rs:12-19). codex NEVER sends a client
// tool's input_schema verbatim: it sanitizes (const→enum, type inference, permissive defaults),
// prunes unreachable $defs, compacts schemas whose normalized form exceeds 5000 bytes through four
// increasingly lossy passes, then re-serializes through a typed subset that DROPS every keyword
// outside {$ref,type,description,encrypted,enum,items,properties,required,additionalProperties,
// anyOf,oneOf,allOf,$defs,definitions} and ALPHABETIZES properties/definition tables (BTreeMap).
// gpt-5.6 therefore only ever sees codex-shaped schemas from its own CLI; splice sending Claude
// Code's schemas verbatim was a per-tool wire divergence on every request (tools byte-parity,
// 2026-08-26). ONE deliberate deviation: where codex FAILS tool registration (a schema its typed
// subset cannot represent — array-form items, a singleton null root type), splice falls back to
// the VERBATIM schema for that tool instead of dropping it — a gateway must not make a client's
// tool vanish (never-below-status-quo). The pipeline's later stages live in sibling files
// ToolSchemaDefPrune.kt / ToolSchemaCompact.kt / ToolSchemaSubset.kt (per-file function ceiling).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val schemaDefTables = listOf("\$defs", "definitions")
internal val schemaChildKeys = listOf("items", "anyOf", "oneOf", "allOf")
internal val schemaCompositionKeys = listOf("anyOf", "oneOf", "allOf")
internal const val SCHEMA_PROPERTIES = "properties"
internal const val SCHEMA_ADDITIONAL_PROPERTIES = "additionalProperties"
internal const val SCHEMA_ITEMS = "items"
internal const val SCHEMA_TYPE = "type"
internal const val SCHEMA_REF = "\$ref"
internal const val SCHEMA_ENUM = "enum"
internal const val SCHEMA_REQUIRED = "required"
internal const val SCHEMA_DESCRIPTION = "description"
internal const val TYPE_STRING = "string"
internal const val TYPE_OBJECT = "object"
internal const val TYPE_ARRAY = "array"
internal const val TYPE_NUMBER = "number"

private const val PREFIX_ITEMS = "prefixItems"
private val knownTypes =
    setOf(TYPE_STRING, TYPE_NUMBER, "boolean", "integer", TYPE_OBJECT, TYPE_ARRAY, "null")
private val objectHintKeys = listOf(SCHEMA_PROPERTIES, SCHEMA_REQUIRED, SCHEMA_ADDITIONAL_PROPERTIES)
private val arrayHintKeys = listOf(SCHEMA_ITEMS, PREFIX_ITEMS)
private val stringHintKeys = listOf(SCHEMA_ENUM, "format")
private val numberHintKeys =
    listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf")

/** A schema shape codex's typed subset cannot represent — codex errors tool registration there; we
 *  unwind to the verbatim fallback in [ToolSchemaNormalizer.normalize]. */
internal class SubsetUnrepresentable : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

internal data class DefPointer(val table: String, val name: String)

/** The primitive shape reads several pipeline stages share. */
internal class SchemaShapes {

    fun isBooleanPrimitive(v: JsonElement): Boolean =
        v is JsonPrimitive && !v.isString && v.content in listOf("true", "false")

    /** JSON Schema `type` as codex's JsonSchemaType parses it: a known primitive name or a list of
     *  them; anything else reads as "no declared type". */
    fun normalizedTypes(t: JsonElement?): List<String> = when (t) {
        is JsonPrimitive -> if (t.isString && t.content in knownTypes) listOf(t.content) else emptyList()
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter { it in knownTypes }
        else -> emptyList()
    }
}

/** sanitize_json_schema (json_schema.rs:466-544): compatibility-lower arbitrary schemas into the
 *  subset the Responses API's non-strict mode is served by codex. */
internal class SchemaSanitize(private val shapes: SchemaShapes) {

    fun sanitize(v: JsonElement): JsonElement = when {
        shapes.isBooleanPrimitive(v) -> buildJsonObject { put(SCHEMA_TYPE, TYPE_STRING) } // bool-form
        v is JsonArray -> JsonArray(v.map { sanitize(it) })
        v is JsonObject -> sanitizeObject(v)
        else -> v
    }

    private fun sanitizeObject(o: JsonObject): JsonObject {
        val m = LinkedHashMap<String, JsonElement>(o)
        sanitizeChildren(m)
        m.remove("const")?.let { m[SCHEMA_ENUM] = JsonArray(listOf(it)) } // const → single-value enum
        val declared = shapes.normalizedTypes(m[SCHEMA_TYPE])
        val escape = declared.isEmpty() &&
            (m.containsKey(SCHEMA_REF) || schemaCompositionKeys.any(m::containsKey))
        val types = if (declared.isEmpty() && !escape) inferTypes(m) else declared
        return when {
            escape -> JsonObject(m) // bare $ref / composition: leave as-is
            types.isEmpty() -> JsonObject(emptyMap()) // no recognized hints → coerce to {}
            else -> {
                writeTypes(m, types)
                ensureDefaultChildren(m, types)
                JsonObject(m)
            }
        }
    }

    private fun sanitizeChildren(m: LinkedHashMap<String, JsonElement>) {
        (m[SCHEMA_PROPERTIES] as? JsonObject)?.let { p ->
            m[SCHEMA_PROPERTIES] = JsonObject(p.mapValues { sanitize(it.value) })
        }
        m[SCHEMA_ITEMS]?.let { m[SCHEMA_ITEMS] = sanitize(it) }
        m[SCHEMA_ADDITIONAL_PROPERTIES]?.let {
            if (!shapes.isBooleanPrimitive(it)) m[SCHEMA_ADDITIONAL_PROPERTIES] = sanitize(it)
        }
        m[PREFIX_ITEMS]?.let { m[PREFIX_ITEMS] = sanitize(it) }
        for (k in schemaCompositionKeys) m[k]?.let { m[k] = sanitize(it) }
        sanitizeDefTables(m)
    }

    /** Malformed (non-object) definition tables drop; valid ones lower recursively — codex's
     *  sanitize_schema_table "degrade gracefully" rule. */
    private fun sanitizeDefTables(m: LinkedHashMap<String, JsonElement>) {
        for (t in schemaDefTables) {
            when (val table = m[t]) {
                null -> Unit
                is JsonObject -> m[t] = JsonObject(table.mapValues { sanitize(it.value) })
                else -> m.remove(t)
            }
        }
    }

    private fun inferTypes(m: Map<String, JsonElement>): List<String> {
        val objectHinted = objectHintKeys.any(m::containsKey)
        val arrayHinted = arrayHintKeys.any(m::containsKey)
        return when {
            objectHinted -> listOf(TYPE_OBJECT)
            arrayHinted -> listOf(TYPE_ARRAY)
            stringHintKeys.any(m::containsKey) -> listOf(TYPE_STRING)
            numberHintKeys.any(m::containsKey) -> listOf(TYPE_NUMBER)
            else -> emptyList()
        }
    }

    private fun writeTypes(m: LinkedHashMap<String, JsonElement>, types: List<String>) {
        m[SCHEMA_TYPE] = if (types.size == 1) {
            JsonPrimitive(types.single())
        } else {
            JsonArray(types.map { JsonPrimitive(it) })
        }
    }

    private fun ensureDefaultChildren(m: LinkedHashMap<String, JsonElement>, types: List<String>) {
        if (TYPE_OBJECT in types && !m.containsKey(SCHEMA_PROPERTIES)) {
            m[SCHEMA_PROPERTIES] = JsonObject(emptyMap())
        }
        if (TYPE_ARRAY in types && !m.containsKey(SCHEMA_ITEMS)) {
            m[SCHEMA_ITEMS] = buildJsonObject { put(SCHEMA_TYPE, TYPE_STRING) }
        }
    }
}

/** The pipeline entry point — see this file's header. */
internal class ToolSchemaNormalizer {

    private val shapes = SchemaShapes()
    private val sanitize = SchemaSanitize(shapes)
    private val prune = SchemaDefPrune(shapes)
    private val subset = SchemaSubset(shapes)
    private val compact = SchemaCompact(prune, subset, shapes)

    /** The full codex pipeline; returns [schema] unchanged when the typed subset cannot represent
     *  the result (this file's header — the one deliberate deviation). */
    fun normalize(schema: JsonObject): JsonObject {
        val sanitized = sanitize.sanitize(withRootProperties(schema)) as? JsonObject ?: return schema
        val compacted = compact.compact(prune.pruneUnreachableDefs(sanitized))
        return try {
            subset.subsetObject(compacted, root = true)
        } catch (_: SubsetUnrepresentable) {
            schema
        }
    }

    /** mcp_tool.rs:12-19 — the backend mandates `properties`; insert `{}` when absent or null. */
    private fun withRootProperties(schema: JsonObject): JsonObject {
        val p = schema[SCHEMA_PROPERTIES]
        if (p != null && p !is JsonPrimitive) return schema
        return JsonObject(LinkedHashMap(schema).also { it[SCHEMA_PROPERTIES] = JsonObject(emptyMap()) })
    }
}
