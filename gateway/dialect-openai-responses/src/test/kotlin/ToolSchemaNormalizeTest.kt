// NEW (tools byte-parity 2026-08-26): pins the ToolSchemaNormalize.kt port against the behaviors
// codex-rs pins in tools/src/json_schema_tests.rs — sanitize's lowering rules, unreachable-$defs
// pruning, the >5KB compaction ladder, the typed-subset keyword drop + alphabetization — plus the
// one deliberate deviation (verbatim fallback where codex fails tool registration).
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.dialect.responses.ToolSchemaNormalizer

private fun js(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

class ToolSchemaNormalizeTest {

    private val n = ToolSchemaNormalizer()

    @Test
    fun `unknown keywords drop and properties alphabetize`() {
        val out = n.normalize(
            js(
                """{"type":"object","${'$'}schema":"http://json-schema.org/draft-07/schema#",
                   "properties":{"zeta":{"type":"string","format":"uri","minLength":1,"default":"x"},
                                 "alpha":{"type":"number","minimum":0}},
                   "required":["zeta"],"additionalProperties":false,"title":"T"}""",
            ),
        )
        assertEquals(
            """{"type":"object","properties":{"alpha":{"type":"number"},"zeta":{"type":"string"}},""" +
                """"required":["zeta"],"additionalProperties":false}""",
            out.toString(),
        )
    }

    @Test
    fun `const collapses to single-value enum and bare objects infer their type`() {
        val out = n.normalize(
            js(
                """{"properties":{"mode":{"const":"fast"},"names":{"items":{"type":"string"}},
                                  "n":{"minimum":2},"fmt":{"format":"date"}}}""",
            ),
        )
        assertEquals(
            """{"type":"object","properties":{""" +
                """"fmt":{"type":"string"},""" +
                """"mode":{"type":"string","enum":["fast"]},""" +
                """"n":{"type":"number"},""" +
                """"names":{"type":"array","items":{"type":"string"}}}}""",
            out.toString(),
        )
    }

    @Test
    fun `boolean-form property schema coerces to accept-all string`() {
        val out = n.normalize(js("""{"type":"object","properties":{"anything":true}}"""))
        assertEquals(
            """{"type":"object","properties":{"anything":{"type":"string"}}}""",
            out.toString(),
        )
    }

    @Test
    fun `object type gains empty properties and array type gains string items`() {
        val out = n.normalize(js("""{"type":"object","properties":{"list":{"type":"array"}}}"""))
        assertEquals(
            """{"type":"object","properties":{"list":{"type":"array","items":{"type":"string"}}}}""",
            out.toString(),
        )
    }

    @Test
    fun `unreachable definitions prune and reachable ones survive`() {
        val out = n.normalize(
            js(
                """{"type":"object",
                   "properties":{"user":{"${'$'}ref":"#/${'$'}defs/User"}},
                   "${'$'}defs":{"User":{"type":"object","properties":{"name":{"type":"string"}}},
                                 "Dead":{"type":"string"}}}""",
            ),
        )
        val defs = out["\$defs"]?.jsonObject
        assertTrue(defs != null && defs.containsKey("User"), "reachable def pruned: $out")
        assertFalse(defs!!.containsKey("Dead"), "unreachable def survived: $out")
    }

    @Test
    fun `oversized schema strips descriptions first`() {
        val big = "x".repeat(6000)
        val out = n.normalize(
            js("""{"type":"object","properties":{"a":{"type":"string","description":"$big"}}}"""),
        )
        assertEquals("""{"type":"object","properties":{"a":{"type":"string"}}}""", out.toString())
    }

    @Test
    fun `a shape the typed subset cannot represent falls back verbatim`() {
        // array-form items: codex fails tool registration; splice must not drop a client's tool.
        val schema = js("""{"type":"object","properties":{"t":{"type":"array","items":[{"type":"string"}]}}}""")
        assertEquals(schema.toString(), n.normalize(schema).toString())
    }

    @Test
    fun `missing root properties is inserted`() {
        assertEquals("""{"type":"object","properties":{}}""", n.normalize(js("""{"type":"object"}""")).toString())
    }
}
