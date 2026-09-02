// PORT-OF: codex-rs tools/src/json_schema.rs @ 63fe5a6, the JsonSchema typed-subset round-trip
// (:41-74) — stage 4 of ToolSchemaNormalize.kt's pipeline: re-serialize through codex's JsonSchema
// struct. Unknown keywords drop (serde's default on unknown fields), fields ride in serde
// declaration order, properties/definition tables alphabetize (BTreeMap). Throws
// [SubsetUnrepresentable] exactly where serde deserialization would error; the normalizer then
// falls back to the verbatim schema (the one deliberate deviation — codex drops the tool).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Head fields in JsonSchema serde declaration order (json_schema.rs:41-74); the structured fields
// (items/properties/required/additionalProperties/compositions/tables) follow in the same order.
private val subsetScalarHead = listOf(SCHEMA_REF, SCHEMA_TYPE, SCHEMA_DESCRIPTION, "encrypted", SCHEMA_ENUM)

internal class SchemaSubset(private val shapes: SchemaShapes) {

    fun subsetObject(o: JsonObject, root: Boolean): JsonObject {
        // deserialize_tool_input_schema:209-218 — a singleton null root type errors registration.
        if (root && (o[SCHEMA_TYPE] as? JsonPrimitive)?.content == "null") throw SubsetUnrepresentable()
        val out = LinkedHashMap<String, JsonElement>()
        subsetScalars(o, out)
        o[SCHEMA_ITEMS]?.let { out[SCHEMA_ITEMS] = subsetObject(asSchema(it), root = false) }
        o[SCHEMA_PROPERTIES]?.let { out[SCHEMA_PROPERTIES] = subsetTable(it) }
        o[SCHEMA_REQUIRED]?.let { out[SCHEMA_REQUIRED] = requiredStrings(it) }
        o[SCHEMA_ADDITIONAL_PROPERTIES]?.let { out[SCHEMA_ADDITIONAL_PROPERTIES] = subsetAdditional(it) }
        subsetGroups(o, out)
        return JsonObject(out)
    }

    private fun subsetGroups(o: JsonObject, out: LinkedHashMap<String, JsonElement>) {
        for (k in schemaCompositionKeys) o[k]?.let { out[k] = subsetVariants(it) }
        for (t in schemaDefTables) o[t]?.let { out[t] = subsetTable(it) }
    }

    private fun subsetScalars(o: JsonObject, out: LinkedHashMap<String, JsonElement>) {
        for (k in subsetScalarHead) o[k]?.let { out[k] = subsetScalar(k, it) }
    }

    private fun subsetScalar(key: String, v: JsonElement): JsonElement {
        val ok = when (key) {
            SCHEMA_REF, SCHEMA_DESCRIPTION -> (v as? JsonPrimitive)?.isString == true
            SCHEMA_TYPE -> shapes.normalizedTypes(v).isNotEmpty()
            "encrypted" -> shapes.isBooleanPrimitive(v)
            else -> v is JsonArray // enum: arbitrary values, but must be an array
        }
        if (!ok) throw SubsetUnrepresentable()
        return v
    }

    private fun subsetTable(v: JsonElement): JsonObject {
        val table = v as? JsonObject ?: throw SubsetUnrepresentable()
        return JsonObject(
            table.entries.sortedBy { it.key }
                .associate { it.key to subsetObject(asSchema(it.value), root = false) },
        )
    }

    private fun requiredStrings(r: JsonElement): JsonArray {
        val arr = r as? JsonArray ?: throw SubsetUnrepresentable()
        val allStrings = arr.all { (it as? JsonPrimitive)?.isString == true }
        if (!allStrings) throw SubsetUnrepresentable()
        return arr
    }

    private fun subsetAdditional(ap: JsonElement): JsonElement =
        if (shapes.isBooleanPrimitive(ap)) ap else subsetObject(asSchema(ap), root = false)

    private fun subsetVariants(c: JsonElement): JsonArray {
        val arr = c as? JsonArray ?: throw SubsetUnrepresentable()
        return JsonArray(arr.map { subsetObject(asSchema(it), root = false) })
    }

    private fun asSchema(v: JsonElement): JsonObject = v as? JsonObject ?: throw SubsetUnrepresentable()
}
