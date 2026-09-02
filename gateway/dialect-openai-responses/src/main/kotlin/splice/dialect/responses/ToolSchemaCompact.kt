// PORT-OF: codex-rs tools/src/json_schema.rs @ 63fe5a6, compact_large_tool_schema (:229-455) —
// stage 3 of ToolSchemaNormalize.kt's pipeline: four increasingly lossy passes shrink a schema
// whose NORMALIZED (typed-subset) form exceeds the 5000-byte budget. Each pass runs only while the
// schema is still over budget, in codex's fixed order: strip descriptions → drop definitions →
// collapse deep objects → prune compositions.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_COMPACT_SCHEMA_BYTES = 5_000
private const val MAX_COMPACT_SCHEMA_DEPTH = 3

/** One lossy compaction step / one subtree rewrite within a compaction pass. */
internal fun interface SchemaRewrite {
    operator fun invoke(v: JsonElement): JsonElement
}

internal class SchemaCompact(
    private val prune: SchemaDefPrune,
    private val subset: SchemaSubset,
    private val shapes: SchemaShapes,
) {

    private val passes: List<SchemaRewrite> = listOf(
        SchemaRewrite { stripDescriptions(it) },
        SchemaRewrite { dropDefinitions(it) },
        SchemaRewrite { collapseDeepObjects(it, depth = 0) },
        SchemaRewrite { pruneCompositions(it) },
    )

    fun compact(root: JsonObject): JsonObject {
        var v = root
        for (pass in passes) {
            if (normalizedLength(v) <= MAX_COMPACT_SCHEMA_BYTES) break
            v = pass(v) as? JsonObject ?: v
        }
        return v
    }

    /** codex measures the budget on the TYPED-SUBSET serialization (compact_normalized_schema_len);
     *  a subset failure reads as length 0 there — "fits" — stopping further passes. */
    private fun normalizedLength(v: JsonObject): Int = try {
        subset.subsetObject(v, root = false).toString().toByteArray(Charsets.UTF_8).size
    } catch (_: SubsetUnrepresentable) {
        0
    }

    private fun stripDescriptions(v: JsonElement): JsonElement = when (v) {
        is JsonArray -> JsonArray(v.map { stripDescriptions(it) })
        is JsonObject -> {
            val m = LinkedHashMap<String, JsonElement>(v)
            m.remove(SCHEMA_DESCRIPTION)
            mapSchemaChildren(m, includeDefs = true) { stripDescriptions(it) }
            JsonObject(m)
        }
        else -> v
    }

    /** Local-ref carriers become `{}` FIRST, then the root tables drop — so behavior never depends
     *  on how a parser treats refs to missing definitions (drop_schema_definitions). */
    private fun dropDefinitions(v: JsonElement): JsonElement {
        val rewritten = rewriteDefRefsToEmpty(v) as? JsonObject ?: return v
        val m = LinkedHashMap<String, JsonElement>(rewritten)
        for (t in schemaDefTables) m.remove(t)
        return JsonObject(m)
    }

    private fun rewriteDefRefsToEmpty(v: JsonElement): JsonElement = when {
        v is JsonArray -> JsonArray(v.map { rewriteDefRefsToEmpty(it) })
        v is JsonObject && (v[SCHEMA_REF] as? JsonPrimitive)?.takeIf { it.isString }
            ?.let { prune.parseLocalRef(it.content) } != null -> JsonObject(emptyMap())
        v is JsonObject -> {
            val m = LinkedHashMap<String, JsonElement>(v)
            mapSchemaChildren(m, includeDefs = false) { rewriteDefRefsToEmpty(it) }
            JsonObject(m)
        }
        else -> v
    }

    private fun collapseDeepObjects(v: JsonElement, depth: Int): JsonElement = when {
        v is JsonArray -> JsonArray(v.map { collapseDeepObjects(it, depth) })
        v is JsonObject && depth >= MAX_COMPACT_SCHEMA_DEPTH && isComplexSchemaObject(v) ->
            JsonObject(emptyMap())
        v is JsonObject -> {
            val m = LinkedHashMap<String, JsonElement>(v)
            mapSchemaChildren(m, includeDefs = false) { collapseDeepObjects(it, depth + 1) }
            JsonObject(m)
        }
        else -> v
    }

    private fun pruneCompositions(v: JsonElement): JsonElement = when {
        v is JsonArray -> JsonArray(v.map { pruneCompositions(it) })
        v is JsonObject && schemaCompositionKeys.any(v::containsKey) -> JsonObject(emptyMap())
        v is JsonObject -> {
            val m = LinkedHashMap<String, JsonElement>(v)
            mapSchemaChildren(m, includeDefs = false) { pruneCompositions(it) }
            JsonObject(m)
        }
        else -> v
    }

    private fun isComplexSchemaObject(o: JsonObject): Boolean =
        schemaChildKeys.any(o::containsKey) || o.containsKey(SCHEMA_PROPERTIES) ||
            o.containsKey(SCHEMA_ADDITIONAL_PROPERTIES) || o.containsKey(SCHEMA_REF)

    private fun mapSchemaChildren(
        m: LinkedHashMap<String, JsonElement>,
        includeDefs: Boolean,
        f: SchemaRewrite,
    ) {
        (m[SCHEMA_PROPERTIES] as? JsonObject)?.let { p ->
            m[SCHEMA_PROPERTIES] = JsonObject(p.mapValues { f(it.value) })
        }
        for (k in schemaChildKeys) m[k]?.let { m[k] = f(it) }
        m[SCHEMA_ADDITIONAL_PROPERTIES]?.let { ap ->
            if (!shapes.isBooleanPrimitive(ap)) m[SCHEMA_ADDITIONAL_PROPERTIES] = f(ap)
        }
        if (includeDefs) {
            for (t in schemaDefTables) {
                (m[t] as? JsonObject)?.let { table -> m[t] = JsonObject(table.mapValues { f(it.value) }) }
            }
        }
    }
}
