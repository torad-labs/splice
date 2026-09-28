package splice.upstream.codemode.v4388

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeManual.NestedTool
import splice.upstream.codemode.CodeModeSchemaTypes

/** V4-388: the exec manual and its schema types, pinned to codex-rs's own expectations
 *  (code-mode-protocol/src/description.rs and json_schema_types_tests.rs, read 2026-09-28). */
class CodeModeManualTest {
    @Test
    fun `identifiers normalize exactly as codex normalizes them`() {
        assertEquals("mcp__ologs__get_profile", CodeModeManual.identifier("mcp__ologs__get_profile"))
        assertEquals("hidden_dynamic_tool", CodeModeManual.identifier("hidden-dynamic-tool"))
        assertEquals("_lives", CodeModeManual.identifier("9lives"))
        assertEquals("_", CodeModeManual.identifier(""))
        assertEquals("a_b", CodeModeManual.identifier("a😀b"))
    }

    @Test
    fun `a nested tool renders its heading, description and typed declaration`() {
        val manual = CodeModeManual.description(
            listOf(
                NestedTool(
                    "hidden-dynamic-tool",
                    "Test tool",
                    json(
                        """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],
                        "additionalProperties":false}""",
                    ),
                ),
                NestedTool("foo", "bar", null),
            ),
            deferred = false,
        )
        assertTrue(manual.startsWith("Run JavaScript code to orchestrate/compose tool calls\n"), manual)
        assertTrue(manual.contains("### `foo`\nbar\n\nexec tool declaration:\n```ts\n"), manual)
        assertTrue(manual.contains("declare const tools: { foo(args: unknown): Promise<unknown>; };"), manual)
        assertTrue(manual.contains("### `hidden_dynamic_tool` (`hidden-dynamic-tool`)\nTest tool"), manual)
        assertTrue(
            manual.contains("hidden_dynamic_tool(args: { city: string; }): Promise<unknown>;"),
            manual,
        )
        assertTrue(manual.indexOf("### `foo`") < manual.indexOf("### `hidden_dynamic_tool`"), "name order")
        assertFalse(manual.contains("Some deferred nested tools"), manual)
    }

    @Test
    fun `property descriptions become comments as codex renders them`() {
        val rendered = CodeModeSchemaTypes.render(
            json(
                """{"type":"object","properties":{"weather":{"type":"array",
                "description":"look up weather for a given list of locations",
                "items":{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}}},
                "required":["weather"]}""",
            ),
        )
        assertEquals(
            "{\n  // look up weather for a given list of locations\n  weather: Array<{ location: string; }>;\n}",
            rendered,
        )
    }

    @Test
    fun `the manual advertises only helpers the runtime provides and the deferred note when asked`() {
        val manual = CodeModeManual.description(emptyList(), deferred = true)
        listOf("`exit()`", "`text(value:", "`ALL_TOOLS`", "`await tools.Bash(...)`").forEach {
            assertTrue(manual.contains(it), it)
        }
        val absent = listOf(
            "`image(", "`audio(", "`store(", "`load(", "`notify(", "`setTimeout(", "`yield_control(", "// @exec:",
            "max_output_tokens", "exec_command", "either a string or an object",
        )
        absent.forEach {
            assertFalse(manual.contains(it), it)
        }
        assertTrue(manual.contains("Some deferred nested tools may be omitted from this description."))
    }

    @Test
    fun `the grammar is codex's CODE_MODE_FREEFORM_GRAMMAR byte for byte`() {
        val codex = """
start: pragma_source | plain_source
pragma_source: PRAGMA_LINE NEWLINE SOURCE
plain_source: SOURCE

PRAGMA_LINE: /[ \t]*\/\/ @exec:[^\r\n]*/
NEWLINE: /\r?\n/
SOURCE: /[\s\S]+/
"""
        assertEquals(codex, CodeModeManual.GRAMMAR)
    }

    @Test
    fun `recursive local refs with escaped pointer segments expand once then stop`() {
        val rendered = CodeModeSchemaTypes.render(
            json(
                """{"type":"object","properties":{"clauses":{"type":"array",
                "items":{"${'$'}ref":"#/${'$'}defs/Boolean~1Clause~0v1"}}},
                "${'$'}defs":{"Boolean/Clause~v1":{"type":"object","properties":{"query":{"${'$'}ref":"#/${'$'}defs/Query"}}},
                "Query":{"oneOf":[{"type":"string"},{"type":"object","properties":{"clauses":{"type":"array",
                "items":{"${'$'}ref":"#/${'$'}defs/Boolean~1Clause~0v1"}}}}]}}}""",
            ),
        )
        assertTrue(rendered.contains("clauses?: Array<{ query?: string | { clauses?: Array<{"), rendered)
        assertTrue(rendered.contains("query?: string | { clauses?: Array<unknown>; };"), rendered)
    }

    @Test
    fun `ref siblings, uri fragments and allOf precedence`() {
        assertEquals(
            "(string) & (\"A\")",
            render("""{"${'$'}ref":"#/${'$'}defs/Label","enum":["A"],"${'$'}defs":{"Label":{"type":"string"}}}"""),
        )
        assertEquals(
            "string",
            render("""{"${'$'}ref":"#/${'$'}defs/Foo%20Bar","${'$'}defs":{"Foo Bar":{"type":"string"}}}"""),
        )
        assertEquals(
            "(string | number) & { value?: string; }",
            render(
                """{"allOf":[{"${'$'}ref":"#/${'$'}defs/Choice"},{"type":"object","properties":{"value":{"type":"string"}}}],
                "${'$'}defs":{"Choice":{"oneOf":[{"type":"string"},{"type":"number"}]}}}""",
            ),
        )
    }

    @Test
    fun `local refs under a nested schema resource stay unresolved`() {
        assertEquals(
            "{ nested?: { value?: unknown; }; }",
            render(
                """{"${'$'}defs":{"Choice":{"type":"string"}},"type":"object","properties":{"nested":{
                "${'$'}id":"urn:nested","${'$'}defs":{"Choice":{"type":"number"}},"type":"object",
                "properties":{"value":{"${'$'}ref":"#/${'$'}defs/Choice"}}}}}""",
            ),
        )
    }

    @Test
    fun `expansions are bounded and dangling refs are not charged`() {
        val missing = (0 until MAX_EXPANSIONS).joinToString(",") {
            "\"a_missing_$it\":{\"${'$'}ref\":\"#/${'$'}defs/Missing$it\"}"
        }
        val dangling = render(
            """{"type":"object","properties":{$missing,"z_valid":{"${'$'}ref":"#/${'$'}defs/Valid"}},
            "${'$'}defs":{"Valid":{"type":"string"}}}""",
        )
        assertTrue(dangling.contains("z_valid?: string;"), dangling)
        val repeated = (0 until MAX_EXPANSIONS + 2).joinToString(",") {
            "\"property_$it\":{\"${'$'}ref\":\"#/${'$'}defs/Item\"}"
        }
        val bounded = render(
            """{"type":"object","properties":{$repeated},"${'$'}defs":{"Item":{"type":"string"}}}""",
        )
        assertEquals(MAX_EXPANSIONS, Regex("string").findAll(bounded).count())
        assertEquals(2, Regex("unknown").findAll(bounded).count())
    }

    @Test
    fun `work and size budgets turn an oversized rendering into unknown`() {
        val longDescription = "x".repeat(MAX_RENDERED_BYTES / 2)
        val repeated = (0 until MAX_EXPANSIONS).joinToString(",") {
            "\"property_$it\":{\"${'$'}ref\":\"#/${'$'}defs/Item\"}"
        }
        assertEquals(
            "unknown",
            render(
                """{"type":"object","properties":{$repeated},"${'$'}defs":{"Item":{"type":"object",
                "properties":{"value":{"type":"string","description":"$longDescription"}}}}}""",
            ),
        )
        val literal = "x".repeat(MAX_RENDERED_BYTES * 4)
        assertEquals(
            "unknown",
            render("""{"${'$'}ref":"#/${'$'}defs/Value","${'$'}defs":{"Value":{"const":"$literal"}}}"""),
        )
        val capped = "x".repeat(MAX_RENDERED_BYTES)
        assertEquals(
            "unknown",
            render("""{"type":"object","properties":{"value":{"type":"string","description":"$capped"}}}"""),
        )
    }

    private fun render(schema: String): String = CodeModeSchemaTypes.render(json(schema))

    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)

    private companion object {
        const val MAX_EXPANSIONS = 32
        const val MAX_RENDERED_BYTES = 16_000
    }
}
