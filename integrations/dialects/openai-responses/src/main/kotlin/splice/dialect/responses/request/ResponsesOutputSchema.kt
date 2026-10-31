// NEW: caller JSON output contracts, distinct from function-tool input strictness.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.RequestFeature
import splice.core.util.JsonScalars
import splice.core.wire.AnthropicRequest
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesOutputSchemaPolicy
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.admission.ResponsesRequestControls
import splice.dialect.responses.schema.ResponsesStrictSchema
import splice.upstream.RequestRefusal

/** Claude Code's schema tool: returned schema text must reach the client as this tool's JSON input. */
internal const val STRUCTURED_OUTPUT_TOOL = "StructuredOutput"

// why: the same schema field is validated, compared with the tool contract, and forwarded unchanged.
private const val SCHEMA_KEY = "schema"

/** The json_schema text format a head sends, if the caller asked for one, and the caller's schema an answer to a
 *  stricter form of it is brought back to ([ResponsesStrictSchema]). */
internal data class ResponsesOutputPlan(val format: JsonObject? = null, val answerSchema: JsonObject? = null)

/** The caller's output schema, sent as written or in the strict form the head's vendor holds, so an answer to it stays
 *  valid against the caller's own schema. */
internal object ResponsesOutputSchema {
    private val choices = setOf("auto", "none", "any", "required", "tool")

    /** A forced schema tool can be enforced by schema-only text, then translated back to its tool input. */
    fun forcesTool(body: AnthropicRequest): Boolean = when (body.toolChoice?.type) {
        "tool" -> body.toolChoice?.name == STRUCTURED_OUTPUT_TOOL
        "any", "required" -> body.tools.size == 1 && body.tools.first().name == STRUCTURED_OUTPUT_TOOL
        else -> false
    }

    fun wireBody(body: AnthropicRequest, format: JsonObject?): AnthropicRequest =
        if (format != null && forcesTool(body)) body.copy(tools = emptyList(), toolChoice = null) else body

    /** A declared schema tool cannot override the caller's explicit prohibition on tool calls. */
    fun toolName(body: AnthropicRequest): String? =
        if (body.toolChoice?.type == "none") {
            null
        } else {
            body.tools.firstOrNull { it.name == STRUCTURED_OUTPUT_TOOL && it.inputSchema != null }?.name
        }

    /** Checked before request construction, through the provider's explicit admission refusal: each refusal is a
     *  request feature this standard cannot carry, Declined before any provider sees it. The line a person reads is
     *  written from the cause, so no refusal here words one. */
    fun refusal(body: AnthropicRequest, raw: JsonObject): RequestRefusal? = refusal(
        body,
        raw,
        ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks()),
    )

    fun refusal(body: AnthropicRequest, raw: JsonObject, quirks: ResponsesQuirks): RequestRefusal? {
        val native = nativeFormat(raw)
        val dynamicTools = ResponsesDynamicToolChanges.availability(body)
        val refused = when {
            !carriesFormat(native) -> RequestFeature.OUTPUT_FORMAT
            !carriesTool(body, native) -> RequestFeature.STRUCTURED_OUTPUT
            ResponsesDocumentAdmission.requiresCitations(body) -> RequestFeature.DOCUMENT_CITATIONS
            dynamicTools.hasUnrepresentableRemoval -> RequestFeature.DYNAMIC_TOOL_REMOVAL
            !carriesChoice(body, dynamicTools) -> RequestFeature.TOOL_CHOICE
            else -> null
        }
        return refused?.let { RequestRefusal(FailureCause.Splice.Declined(Decline.Untranslatable(it))) }
            ?: sourceRefusal(body, raw, quirks)
    }

    private fun sourceRefusal(
        body: AnthropicRequest,
        raw: JsonObject,
        quirks: ResponsesQuirks,
    ): RequestRefusal? = ResponsesRequestControls(quirks).refusal(raw)
        ?: ResponsesToolDeclarationAdmission().refusal(body)
        ?: ResponsesToolStrictness(quirks).refusal(body)

    /** No output format, or a json_schema format object that holds a schema object. */
    private fun carriesFormat(native: JsonElement?): Boolean = native == null ||
        native is JsonObject && JsonScalars.str(native, "type") == "json_schema" && native[SCHEMA_KEY] is JsonObject

    /** No StructuredOutput tool, or one with an input_schema that agrees with the output format's schema. */
    private fun carriesTool(body: AnthropicRequest, native: JsonElement?): Boolean {
        val tool = body.tools.firstOrNull { it.name == STRUCTURED_OUTPUT_TOOL } ?: return true
        return tool.inputSchema != null && (native !is JsonObject || tool.inputSchema == native[SCHEMA_KEY])
    }

    /** A choice can only name a function the source timeline leaves callable at this turn's boundary. */
    private fun carriesChoice(
        body: AnthropicRequest,
        dynamicTools: ResponsesDynamicToolChanges.Availability,
    ): Boolean {
        val choice = body.toolChoice ?: return true
        return when (choice.type) {
            "tool" -> choice.name in dynamicTools.currentNames
            "any", "required" -> dynamicTools.currentNames.isNotEmpty()
            else -> choice.type in choices
        }
    }

    private fun nativeFormat(raw: JsonObject): JsonElement? {
        val outputConfig = raw["output_config"] as? JsonObject
        return if (outputConfig?.containsKey("format") == true) {
            outputConfig["format"]?.takeUnless { it is JsonNull }
        } else {
            raw["output_format"]
        }
    }

    /** The caller's schema as a json_schema text format, strict over it as written. */
    fun format(body: AnthropicRequest, raw: JsonObject): JsonObject? =
        plan(body, raw, ResponsesOutputSchemaPolicy.StrictAsGiven).format

    /** The text format for the caller's schema under [policy]. A rewrite into the vendor's strict subset goes strict.
     *  One that made an optional field nullable goes only when the answer reaches the caller as a tool's input, which
     *  is brought back to the caller's schema whole; streamed text cannot be. Otherwise the schema goes as written
     *  with strict false. */
    fun plan(body: AnthropicRequest, raw: JsonObject, policy: ResponsesOutputSchemaPolicy): ResponsesOutputPlan {
        val native = nativeFormat(raw) as? JsonObject
        val tool = body.tools.firstOrNull { it.name == STRUCTURED_OUTPUT_TOOL }
        val schema = (native?.get(SCHEMA_KEY) as? JsonObject) ?: tool?.inputSchema ?: return ResponsesOutputPlan()
        val name = JsonScalars.str(native, "name") ?: "structured_output"
        return when (policy) {
            ResponsesOutputSchemaPolicy.StrictAsGiven -> ResponsesOutputPlan(textFormat(name, schema, strict = true))
            ResponsesOutputSchemaPolicy.ConstrainedWithoutStrict ->
                ResponsesOutputPlan(textFormat(name, schema, strict = false))
            is ResponsesOutputSchemaPolicy.StrictWithin -> {
                val rewritten = ResponsesStrictSchema.rewrite(schema, policy.subset)
                    ?.takeIf { !it.nullable || toolName(body) != null }
                if (rewritten == null) {
                    ResponsesOutputPlan(textFormat(name, schema, strict = false))
                } else {
                    ResponsesOutputPlan(
                        textFormat(name, rewritten.schema, strict = true),
                        answerSchema = schema.takeIf { rewritten.nullable },
                    )
                }
            }
        }
    }

    private fun textFormat(name: String, schema: JsonObject, strict: Boolean): JsonObject = buildJsonObject {
        put("type", "json_schema")
        put("name", name)
        put(SCHEMA_KEY, schema)
        put("strict", strict)
    }
}
