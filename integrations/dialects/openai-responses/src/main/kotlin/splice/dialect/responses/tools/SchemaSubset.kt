// PORT-OF: codex-rs tools/src/json_schema.rs @ 63fe5a6, the JsonSchema typed-subset round-trip
// (:41-74) — stage 4 of ToolSchemaNormalize.kt's pipeline: re-serialize through codex's JsonSchema
// struct. Unknown keywords drop (serde's default on unknown fields), fields ride in serde
// declaration order, properties/definition tables alphabetize (BTreeMap). Answers null
// exactly where serde deserialization would error; the normalizer then falls back to the
// verbatim schema (the one deliberate deviation — codex drops the tool).
package splice.dialect.responses.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Head fields in JsonSchema serde declaration order (json_schema.rs:41-74); the structured fields
// (items/properties/required/additionalProperties/compositions/tables) follow in the same order.
private val subsetScalarHead = listOf(SCHEMA_REF, SCHEMA_TYPE, SCHEMA_DESCRIPTION, "encrypted", SCHEMA_ENUM)

// The order the subset writes its fields in: the scalar head, then the structured fields, the compositions, the tables.
private val subsetFieldOrder = subsetScalarHead +
    listOf(SCHEMA_ITEMS, SCHEMA_PROPERTIES, SCHEMA_REQUIRED, SCHEMA_ADDITIONAL_PROPERTIES) +
    schemaCompositionKeys + schemaDefTables

internal class SchemaSubset(private val shapes: SchemaShapes) {

    /** The typed subset of [o], or null where serde deserialization would error: a wrong shape anywhere below. */
    fun subsetObject(o: JsonObject, root: Boolean): JsonObject? {
        // deserialize_tool_input_schema:209-218 — a singleton null root type errors registration.
        if (root && (o[SCHEMA_TYPE] as? JsonPrimitive)?.content == "null") return null
        val out = LinkedHashMap<String, JsonElement>()
        for (key in subsetFieldOrder) {
            val value = o[key] ?: continue
            out[key] = subsetField(key, value) ?: return null
        }
        return JsonObject(out)
    }

    private fun subsetField(key: String, v: JsonElement): JsonElement? = when (key) {
        in subsetScalarHead -> subsetScalar(key, v)
        SCHEMA_ITEMS -> child(v)
        SCHEMA_PROPERTIES -> subsetTable(v)
        SCHEMA_REQUIRED -> requiredStrings(v)
        SCHEMA_ADDITIONAL_PROPERTIES -> subsetAdditional(v)
        in schemaCompositionKeys -> subsetVariants(v)
        else -> subsetTable(v)
    }

    private fun subsetScalar(key: String, v: JsonElement): JsonElement? {
        val ok = when (key) {
            SCHEMA_REF, SCHEMA_DESCRIPTION -> (v as? JsonPrimitive)?.isString == true
            SCHEMA_TYPE -> shapes.normalizedTypes(v).isNotEmpty()
            "encrypted" -> shapes.isBooleanPrimitive(v)
            else -> v is JsonArray // enum: arbitrary values, but must be an array
        }
        return v.takeIf { ok }
    }

    private fun subsetTable(v: JsonElement): JsonObject? {
        val table = v as? JsonObject ?: return null
        val entries = table.entries.sortedBy { it.key }
            .mapNotNull { entry -> child(entry.value)?.let { entry.key to it } }
        return if (entries.size == table.size) JsonObject(entries.toMap()) else null
    }

    private fun requiredStrings(r: JsonElement): JsonArray? =
        (r as? JsonArray)?.takeIf { arr -> arr.all { (it as? JsonPrimitive)?.isString == true } }

    private fun subsetAdditional(ap: JsonElement): JsonElement? = if (shapes.isBooleanPrimitive(ap)) ap else child(ap)

    private fun subsetVariants(c: JsonElement): JsonArray? {
        val arr = c as? JsonArray ?: return null
        val variants = arr.mapNotNull { child(it) }
        return if (variants.size == arr.size) JsonArray(variants) else null
    }

    /** A schema nested below: an object that itself subsets, or null. */
    private fun child(v: JsonElement): JsonObject? = (v as? JsonObject)?.let { subsetObject(it, root = false) }
}
