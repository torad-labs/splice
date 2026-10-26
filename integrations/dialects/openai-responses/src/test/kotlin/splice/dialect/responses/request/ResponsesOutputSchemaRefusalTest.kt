// NEW: failure-handling slice 2. A request the Responses standard cannot carry is refused before any provider sees it,
// as the one request feature it cannot carry, and the refusal is that cause alone: the line a person reads is written
// from it (final-design.md section 10), so no refusal words one. A request the standard carries is not refused. The
// cases are the ones the refusal's old sentences named, and when a request holds more than one, the feature checked
// first is the one refused, as before: the output format, then the StructuredOutput tool, then the tool choice.
package splice.dialect.responses.request

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.RequestFeature
import splice.core.parse.AnthropicParse

private const val SCHEMA = """{"type":"object","properties":{"answer":{"type":"string"}}}"""
private const val OTHER_SCHEMA = """{"type":"object","properties":{"count":{"type":"integer"}}}"""
private const val READ = """{"name":"Read","input_schema":{"type":"object"}}"""
private const val STRUCTURED = """{"name":"StructuredOutput","input_schema":$SCHEMA}"""
private const val UNSCHEMED = """{"name":"StructuredOutput"}"""

class ResponsesOutputSchemaRefusalTest {

    /** What ResponsesOutputSchema answers a request carrying [fields] beside its model and one message. */
    private fun causeOf(fields: String): FailureCause? {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-5.6","max_tokens":1024,"messages":[{"role":"user","content":"hi"}]$fields}""",
        )
        return ResponsesOutputSchema.refusal(body.typed, body.raw)?.cause
    }

    private fun declined(what: RequestFeature) = FailureCause.Splice.Declined(Decline.Untranslatable(what))

    @Test
    fun `an output format the standard cannot carry is refused as the output format`() {
        listOf(
            ""","output_format":"json"""",
            ""","output_format":{"type":"json_object"}""",
            ""","output_format":{"type":"json_schema"}""",
            ""","output_format":{"type":"json_schema","schema":"object"}""",
            ""","output_config":{"format":{"type":"text"}}""",
        ).forEach { fields -> assertEquals(declined(RequestFeature.OUTPUT_FORMAT), causeOf(fields), fields) }
    }

    @Test
    fun `a StructuredOutput tool the output format cannot stand for is refused as structured output`() {
        listOf(
            ""","tools":[$UNSCHEMED]""",
            ""","output_format":{"type":"json_schema","schema":$SCHEMA},"tools":[$UNSCHEMED]""",
            ""","output_format":{"type":"json_schema","schema":$OTHER_SCHEMA},"tools":[$STRUCTURED]""",
        ).forEach { fields -> assertEquals(declined(RequestFeature.STRUCTURED_OUTPUT), causeOf(fields), fields) }
    }

    @Test
    fun `a tool choice the standard cannot carry is refused as the tool choice`() {
        listOf(
            ""","tool_choice":{"type":"tool","name":"Missing"}""",
            ""","tools":[$READ],"tool_choice":{"type":"tool","name":"Missing"}""",
            ""","tool_choice":{"type":"any"}""",
            ""","tool_choice":{"type":"required"}""",
            ""","tools":[$READ],"tool_choice":{"type":"sometimes"}""",
        ).forEach { fields -> assertEquals(declined(RequestFeature.TOOL_CHOICE), causeOf(fields), fields) }
    }

    @Test
    fun `a request holding more than one is refused as the first checked, format then tool then choice`() {
        val choice = ""","tool_choice":{"type":"tool","name":"Missing"}"""

        assertEquals(
            declined(RequestFeature.OUTPUT_FORMAT),
            causeOf(""","output_format":{"type":"json_object"},"tools":[$UNSCHEMED]$choice"""),
        )
        assertEquals(declined(RequestFeature.STRUCTURED_OUTPUT), causeOf(""","tools":[$UNSCHEMED]$choice"""))
    }

    @Test
    fun `a request the standard carries is not refused`() {
        val format = ""","output_format":{"type":"json_schema","schema":$SCHEMA}"""
        listOf(
            "",
            format,
            ""","output_config":{"format":{"type":"json_schema","schema":$SCHEMA}}""",
            """$format,"tools":[$STRUCTURED],"tool_choice":{"type":"tool","name":"StructuredOutput"}""",
            ""","tools":[$STRUCTURED]""",
            ""","tools":[$READ],"tool_choice":{"type":"any"}""",
            ""","tools":[$READ],"tool_choice":{"type":"required"}""",
            ""","tools":[$READ],"tool_choice":{"type":"auto"}""",
            ""","tool_choice":{"type":"none"}""",
        ).forEach { fields -> assertNull(causeOf(fields), fields) }
    }
}
