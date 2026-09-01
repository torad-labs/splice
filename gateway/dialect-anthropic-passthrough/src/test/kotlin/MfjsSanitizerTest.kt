// NEW: unit test the Moonshot-Flavored JSON Schema sanitizer — explicit-type inference, anyOf/
// oneOf/allOf parent-type pushdown, tuple-items collapse, $ref sibling stripping, key blocklist,
// depth cap, and $ref-cycle termination. Pure function; every case is a JsonObject -> JsonObject
// assertion.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.dialect.passthrough.MfjsSanitizer

private fun sanitize(json: String): JsonObject =
    MfjsSanitizer.sanitize(Json.parseToJsonElement(json).jsonObject)

private fun JsonObject.type() = this["type"]?.jsonPrimitive?.content

class MfjsSanitizerTest {

    @Test
    fun `object array and leaf types are inferred when absent`() {
        val out = sanitize("""{"properties":{"a":{},"b":{"items":{}}}}""")
        assertEquals("object", out.type())
        val props = out["properties"]!!.jsonObject
        assertEquals("string", props["a"]!!.jsonObject.type()) // bare leaf -> string
        assertEquals("array", props["b"]!!.jsonObject.type()) // has items -> array
        assertEquals("string", props["b"]!!.jsonObject["items"]!!.jsonObject.type())
    }

    @Test
    fun `enum type comes from the first literal`() {
        assertEquals("string", sanitize("""{"enum":["x","y"]}""").type())
        assertEquals("boolean", sanitize("""{"enum":[true]}""").type())
        assertEquals("integer", sanitize("""{"enum":[7]}""").type())
        assertEquals("number", sanitize("""{"enum":[1.5]}""").type())
    }

    @Test
    fun `a parent with anyOf loses its type and every branch gains one`() {
        val out = sanitize(
            """{"type":"string","anyOf":[{"type":"string"},{"description":"no type here"}]}""",
        )
        assertNull(out["type"]) // parent type removed (rule 2)
        val branches = out["anyOf"]!!.jsonArray.map { it.jsonObject }
        assertEquals("string", branches[0].type())
        assertEquals("string", branches[1].type()) // inferred (rule 1 recursively)
    }

    @Test
    fun `oneOf is treated like anyOf`() {
        val out = sanitize("""{"type":"object","oneOf":[{"description":"x"}]}""")
        assertNull(out["type"])
        assertEquals("string", out["oneOf"]!!.jsonArray.single().jsonObject.type())
    }

    // --- allOf: the third combinator (DR-123) --------------------------------------------------
    // Before the repair `allOf` was neither hub nor child: the node kept a sibling type AND the
    // branch list rode through the `else` arm verbatim, so a composed schema reached Moonshot with
    // an un-sanitized branch list beside a type its branches contradict.

    @Test
    fun `allOf is a combinator hub and its branches are sanitized`() {
        val out = sanitize(
            """{"type":"string","allOf":[
                 {"properties":{"a":{"type":"string","format":"email"}}},
                 {"${'$'}ref":"#/${'$'}defs/B","description":"drop me"}]}""",
        )
        assertNull(out["type"]) // hub keeps no type — the branches carry their own (rule 2)
        val branches = out["allOf"]!!.jsonArray.map { it.jsonObject }
        assertEquals("object", branches[0].type()) // inferred one level down (rule 1)
        val a = branches[0]["properties"]!!.jsonObject["a"]!!.jsonObject
        assertNull(a["format"], "the blocklist must reach inside an allOf branch")
        assertEquals("#/\$defs/B", branches[1]["\$ref"]?.jsonPrimitive?.content)
        assertEquals(1, branches[1].size) // the $ref rule applies to a branch too
    }

    /** The corruption the row names: with no explicit type at the hub, [inferType]'s fallback
     *  stamped `string` beside a branch list that describes an object. */
    @Test
    fun `a bare allOf hub is not stamped with an inferred type`() {
        val out = sanitize("""{"allOf":[{"type":"object","properties":{"a":{"type":"string"}}}]}""")
        assertNull(out["type"])
    }

    /** Ordering pin, green before and after: $ref wins over every combinator, so a node carrying
     *  both is still reduced to its $ref. Widening [hasCombinator] must not move that check. */
    @Test
    fun `a ref node with an allOf sibling still keeps only the ref`() {
        val out = sanitize("""{"${'$'}ref":"#/${'$'}defs/Foo","allOf":[{"type":"object"}],"description":"x"}""")
        assertEquals("#/\$defs/Foo", out["\$ref"]?.jsonPrimitive?.content)
        assertEquals(1, out.size)
    }

    /** A non-array `allOf` is not a branch list, so it rides verbatim — keys and all — exactly as a
     *  non-array anyOf does. It is still a combinator for the type rule. */
    @Test
    fun `a non-array allOf rides verbatim and still suppresses the hub type`() {
        val out = sanitize("""{"allOf":{"type":"object","format":"email"}}""")
        assertNull(out["type"])
        val kept = out["allOf"]!!.jsonObject
        assertEquals("object", kept.type())
        assertEquals("email", kept["format"]?.jsonPrimitive?.content)
    }

    @Test
    fun `allOf branches are depth-counted and collapse at the cap`() {
        val depth = 15
        val json = buildString {
            repeat(depth) { append("""{"allOf":[""") }
            append("{}")
            repeat(depth) { append("]}") }
        }
        var node = sanitize(json)
        var steps = 0
        while (node.containsKey("allOf") && steps < depth) {
            node = node["allOf"]!!.jsonArray.single().jsonObject
            steps++
        }
        assertEquals("object", node.type())
        assertFalse(node.containsKey("allOf"))
        assertTrue(steps < depth, "collapse must happen before the full nesting depth")
    }

    @Test
    fun `tuple-style items collapse to the first item schema`() {
        val out = sanitize("""{"type":"array","items":[{"type":"string"},{"type":"number"}]}""")
        assertEquals("string", out["items"]!!.jsonObject.type())
    }

    @Test
    fun `empty tuple items become an empty schema`() {
        val out = sanitize("""{"type":"array","items":[]}""")
        assertTrue(out["items"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `a ref node keeps only the ref, dropping siblings`() {
        val out = sanitize("""{"${'$'}ref":"#/${'$'}defs/Foo","type":"object","description":"drop me"}""")
        assertEquals("#/\$defs/Foo", out["\$ref"]?.jsonPrimitive?.content)
        assertEquals(1, out.size)
    }

    @Test
    fun `blocklisted keys are stripped everywhere`() {
        val out = sanitize(
            """{"type":"string","format":"email","title":"T","${'$'}comment":"c","${'$'}schema":"http://x",
                "exclusiveMinimum":1,"exclusiveMaximum":9,"minContains":1,"maxContains":2,"prefixItems":[]}""",
        )
        val stripped = listOf(
            "format",
            "title",
            "\$comment",
            "\$schema",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "minContains",
            "maxContains",
            "prefixItems",
        )
        stripped.forEach { assertNull(out[it], "expected $it stripped") }
        assertEquals("string", out.type())
    }

    @Test
    fun `defs subtree is preserved and sanitized`() {
        val out = sanitize(
            """{"type":"object","${'$'}defs":{"Node":{"properties":{"v":{"format":"int32"}}}}}""",
        )
        val node = out["\$defs"]!!.jsonObject["Node"]!!.jsonObject
        assertEquals("object", node.type()) // inferred
        val v = node["properties"]!!.jsonObject["v"]!!.jsonObject
        assertEquals("string", v.type())
        assertNull(v["format"]) // stripped inside $defs too
    }

    @Test
    fun `deeply nested schema collapses at the depth cap and terminates`() {
        val depth = 15
        val json = buildString {
            repeat(depth) { append("""{"type":"object","properties":{"a":""") }
            append("{}")
            repeat(depth) { append("}}") }
        }
        var node = sanitize(json)
        // Walk down; beyond the cap the subtree is exactly {"type":"object"} with no properties.
        var steps = 0
        while (node.containsKey("properties") && steps < depth) {
            node = node["properties"]!!.jsonObject["a"]!!.jsonObject
            steps++
        }
        assertEquals("object", node.type())
        assertFalse(node.containsKey("properties"))
        assertTrue(steps < depth, "collapse must happen before the full nesting depth")
    }

    @Test
    fun `cyclic ref via defs terminates and keeps the ref leaf`() {
        val out = sanitize(
            """{"type":"object",
                "${'$'}defs":{"Node":{"type":"object","properties":{"next":{"${'$'}ref":"#/${'$'}defs/Node"}}}},
                "properties":{"root":{"${'$'}ref":"#/${'$'}defs/Node"}}}""",
        )
        val next = out["\$defs"]!!.jsonObject["Node"]!!.jsonObject["properties"]!!
            .jsonObject["next"]!!.jsonObject
        assertEquals("#/\$defs/Node", next["\$ref"]?.jsonPrimitive?.content)
        assertEquals(1, next.size) // only $ref survived
        val root = out["properties"]!!.jsonObject["root"]!!.jsonObject
        assertEquals("#/\$defs/Node", root["\$ref"]?.jsonPrimitive?.content)
    }
}
