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
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.admission.ResponsesRequestControls
import splice.upstream.RequestRefusal

/** Claude Code's schema tool: returned schema text must reach the client as this tool's JSON input. */
internal const val STRUCTURED_OUTPUT_TOOL = "StructuredOutput"

// why: the same schema field is validated, compared with the tool contract, and forwarded unchanged.
private const val SCHEMA_KEY = "schema"

/** The caller's output schema, forwarded unchanged so the caller's own validation retains its meaning. */
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

    fun format(body: AnthropicRequest, raw: JsonObject): JsonObject? {
        val native = nativeFormat(raw) as? JsonObject
        val tool = body.tools.firstOrNull { it.name == STRUCTURED_OUTPUT_TOOL }
        val schema = (native?.get(SCHEMA_KEY) as? JsonObject) ?: tool?.inputSchema ?: return null
        return buildJsonObject {
            put("type", "json_schema")
            put("name", JsonScalars.str(native, "name") ?: "structured_output")
            put(SCHEMA_KEY, schema)
            put("strict", true)
        }
    }
}
