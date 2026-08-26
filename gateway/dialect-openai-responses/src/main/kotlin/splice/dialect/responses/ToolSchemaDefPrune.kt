// PORT-OF: codex-rs tools/src/json_schema.rs @ 63fe5a6, prune_unreachable_definitions
// (:593-720) — drop root $defs/definitions entries the schema never references, so the request
// never spends tokens on dead definitions. Stage 2 of ToolSchemaNormalize.kt's pipeline.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// "%XY" — the escape char plus two hex digits.
private const val PERCENT_ESCAPE_LEN = 3
private const val HEX_RADIX = 16

/** One schema child (a properties value, items, a composition arm, a non-bool
 *  additionalProperties) as the def-reachability walk reaches it. */
internal fun interface SchemaChildVisitor {
    operator fun invoke(child: JsonElement)
}

internal class SchemaDefPrune(private val shapes: SchemaShapes) {

    fun pruneUnreachableDefs(root: JsonObject): JsonObject {
        val reachable = reachableDefs(root)
        val m = LinkedHashMap<String, JsonElement>(root)
        for (t in schemaDefTables) {
            (m[t] as? JsonObject)?.let { table ->
                val kept = table.filterKeys { DefPointer(t, it) in reachable }
                if (kept.isEmpty()) m.remove(t) else m[t] = JsonObject(kept)
            }
        }
        return JsonObject(m)
    }

    private fun reachableDefs(root: JsonObject): Set<DefPointer> {
        val pending = ArrayDeque<DefPointer>()
        collectRefs(root, pending, intoDefs = false)
        val reachable = HashSet<DefPointer>()
        while (pending.isNotEmpty()) {
            val ptr = pending.removeLast()
            if (reachable.add(ptr)) {
                (root[ptr.table] as? JsonObject)?.get(ptr.name)
                    ?.let { collectRefs(it, pending, intoDefs = true) }
            }
        }
        return reachable
    }

    /** intoDefs=false walks only the schema-child graph (definition tables skipped) — codex's
     *  collect_refs_outside_definitions; intoDefs=true (inside a referenced definition) walks
     *  EVERY value, matching collect_refs. */
    private fun collectRefs(v: JsonElement, out: ArrayDeque<DefPointer>, intoDefs: Boolean) {
        when (v) {
            is JsonArray -> v.forEach { collectRefs(it, out, intoDefs) }
            is JsonObject -> {
                (v[SCHEMA_REF] as? JsonPrimitive)?.takeIf { it.isString }
                    ?.let { parseLocalRef(it.content) }?.let(out::add)
                if (intoDefs) {
                    v.values.forEach { collectRefs(it, out, intoDefs) }
                } else {
                    forEachSchemaChild(v) { collectRefs(it, out, intoDefs) }
                }
            }
            else -> Unit
        }
    }

    private fun forEachSchemaChild(o: JsonObject, visit: SchemaChildVisitor) {
        (o[SCHEMA_PROPERTIES] as? JsonObject)?.values?.forEach { visit(it) }
        for (k in schemaChildKeys) o[k]?.let { visit(it) }
        o[SCHEMA_ADDITIONAL_PROPERTIES]?.let { if (!shapes.isBooleanPrimitive(it)) visit(it) }
    }

    /** "#/$defs/Name..." / "#/definitions/Name..." → table+name; null on anything else. A NESTED
     *  pointer ("#/$defs/User/properties/name") keeps the parent definition reachable, exactly like
     *  parse_local_definition_ref (json_schema.rs:702-720). */
    fun parseLocalRef(ref: String): DefPointer? {
        val tokens = ref.takeIf { it.startsWith("#/") }?.removePrefix("#")
            ?.split('/')?.drop(1)
            ?.map { percentDecode(it)?.replace("~1", "/")?.replace("~0", "~") ?: return null }
        return tokens?.takeIf { it.size >= 2 && it[0] in schemaDefTables }
            ?.let { DefPointer(it[0], it[1]) }
    }

    /** Mirror of the urlencoding::decode step; null on malformed input (codex: ref contributes
     *  nothing, so the definition it names is prunable — the same outcome). */
    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                val hex = s.drop(i + 1).take(2).takeIf { it.length == 2 }?.toIntOrNull(HEX_RADIX)
                    ?: return null
                bytes.write(hex)
                i += PERCENT_ESCAPE_LEN
            } else {
                bytes.write(s[i].code)
                i += 1
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
