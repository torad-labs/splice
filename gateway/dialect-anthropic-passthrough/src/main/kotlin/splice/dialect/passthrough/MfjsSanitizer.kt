// NEW: (no Node source) pure JsonObject -> JsonObject rewrite of a JSON Schema into Moonshot-
// Flavored JSON Schema (MFJS, "walle" spec) — the shape Kimi's Anthropic tool surface accepts.
// Invariants: total function (terminates on ANY input via DEPTH_CAP; never throws); every schema
// node gets an explicit `type` unless it is an anyOf/oneOf hub or a $ref; a $ref node keeps ONLY
// the $ref key; tuple `items`/`prefixItems` collapse to a single item schema; a fixed key blocklist
// is stripped at every node. No expansion of $ref targets, so a cyclic $ref cannot recurse — the
// depth cap is the belt-and-suspenders guard for pathologically deep (but finite) trees.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

public object MfjsSanitizer {

    /** Rewrite an input JSON Schema object into MFJS. Pure; terminating; never throws. */
    public fun sanitize(schema: JsonObject): JsonObject = sanitizeNode(schema, 0)

    private fun sanitizeNode(node: JsonObject, depth: Int): JsonObject {
        // Depth cap: collapse anything deeper than the cap to a bare object schema (rule 6).
        if (depth >= DEPTH_CAP) return OBJECT_SCHEMA

        // $ref node: keep ONLY the $ref key, drop every sibling (rule 5).
        node[REF]?.let { ref -> return buildJsonObject { put(REF, ref) } }

        // A node carrying anyOf/oneOf is a combinator hub: it must NOT keep a sibling `type`;
        // instead each branch carries its own type (rule 2, applied recursively via sanitizeNode).
        val hasCombinator = node.containsKey(ANY_OF) || node.containsKey(ONE_OF)

        return buildJsonObject {
            putNodeType(node, hasCombinator)
            for ((key, value) in node) {
                if (key == TYPE || key in STRIP_KEYS) continue
                putSanitizedChild(key, value, depth)
            }
        }
    }

    /** Rule 1/2: a combinator hub carries no type; otherwise keep the explicit type or infer one. */
    private fun JsonObjectBuilder.putNodeType(node: JsonObject, hasCombinator: Boolean) {
        if (hasCombinator) return
        val existing = node[TYPE]
        if (existing != null) put(TYPE, existing) else put(TYPE, inferType(node))
    }

    private fun JsonObjectBuilder.putSanitizedChild(key: String, value: JsonElement, depth: Int) {
        when (key) {
            PROPERTIES -> put(PROPERTIES, sanitizeSchemaMap(value, depth))
            ITEMS -> put(ITEMS, sanitizeItems(value, depth))
            ADDITIONAL_PROPERTIES -> put(ADDITIONAL_PROPERTIES, sanitizeChild(value, depth + 1))
            ANY_OF, ONE_OF -> put(key, sanitizeBranches(value, depth))
            DEFS, DEFINITIONS -> put(key, sanitizeSchemaMap(value, depth))
            else -> put(key, value)
        }
    }

    /** `properties` / `$defs` shape: name -> schema; each value is a schema one level deeper. */
    private fun sanitizeSchemaMap(value: JsonElement, depth: Int): JsonElement {
        val obj = value as? JsonObject ?: return value
        return buildJsonObject { for ((name, child) in obj) put(name, sanitizeChild(child, depth + 1)) }
    }

    /** anyOf/oneOf branch list: each branch is a schema; rule 1 fills a type on every branch. */
    private fun sanitizeBranches(value: JsonElement, depth: Int): JsonElement {
        val arr = value as? JsonArray ?: return value
        return buildJsonArray { arr.forEach { add(sanitizeChild(it, depth + 1)) } }
    }

    /** Tuple-style `items` array collapses to its first element (or {} when empty) — rule 3. */
    private fun sanitizeItems(value: JsonElement, depth: Int): JsonElement = when (value) {
        is JsonArray -> value.firstOrNull()?.let { sanitizeChild(it, depth + 1) } ?: EMPTY_SCHEMA
        else -> sanitizeChild(value, depth + 1)
    }

    private fun sanitizeChild(element: JsonElement, depth: Int): JsonElement =
        if (element is JsonObject) sanitizeNode(element, depth) else element

    /** Infer a type when none is present: object (has properties), array (has items),
     *  enum (type of the first literal), else string. */
    private fun inferType(node: JsonObject): String = when {
        node.containsKey(PROPERTIES) -> OBJECT
        node.containsKey(ITEMS) -> ARRAY
        node[ENUM] is JsonArray -> enumType((node[ENUM] as JsonArray).firstOrNull())
        else -> STRING
    }

    private fun enumType(first: JsonElement?): String {
        val p = (first as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return STRING
        return when {
            p.isString -> STRING
            p.booleanOrNull != null -> BOOLEAN
            p.longOrNull != null -> INTEGER
            p.doubleOrNull != null -> NUMBER
            else -> STRING
        }
    }

    private const val DEPTH_CAP = 10

    private const val REF = "\$ref"
    private const val TYPE = "type"
    private const val PROPERTIES = "properties"
    private const val ITEMS = "items"
    private const val ADDITIONAL_PROPERTIES = "additionalProperties"
    private const val ANY_OF = "anyOf"
    private const val ONE_OF = "oneOf"
    private const val DEFS = "\$defs"
    private const val DEFINITIONS = "definitions"
    private const val ENUM = "enum"

    private const val OBJECT = "object"
    private const val ARRAY = "array"
    private const val STRING = "string"
    private const val BOOLEAN = "boolean"
    private const val INTEGER = "integer"
    private const val NUMBER = "number"

    // Keys Moonshot rejects (or that break its validator) — stripped at every node (rule 4).
    private val STRIP_KEYS = setOf(
        "\$schema", "title", "\$comment", "format",
        "exclusiveMinimum", "exclusiveMaximum", "minContains", "maxContains", "prefixItems",
    )

    private val OBJECT_SCHEMA = buildJsonObject { put(TYPE, OBJECT) }
    private val EMPTY_SCHEMA = buildJsonObject { }
}
