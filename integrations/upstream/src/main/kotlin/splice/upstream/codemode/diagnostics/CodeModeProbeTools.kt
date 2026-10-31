package splice.upstream.codemode.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeToolDefinitions
import java.math.BigInteger

internal object CodeModeProbeTools {
    fun read(input: JsonElement?, codeModeInputSchemaMaxBytes: Int? = null): List<CodeModeManual.NestedTool> =
        (input as? JsonArray).orEmpty().map { value ->
            val tool = value as JsonObject
            CodeModeManual.NestedTool(
                requireNotNull(JsonScalars.str(tool, "name")),
                JsonScalars.str(tool, "description").orEmpty(),
                tool["input_schema"]?.takeUnless { it is JsonNull },
                tool["output_schema"]?.takeUnless { it is JsonNull },
                metadata(tool).let { metadata ->
                    metadata.copy(
                        codeModeInputSchemaMaxBytes = codeModeInputSchemaMaxBytes
                            ?: metadata.codeModeInputSchemaMaxBytes,
                    )
                },
            )
        }

    private fun metadata(tool: JsonObject): CodeModeManual.Metadata = CodeModeManual.Metadata(
        kind = if (JsonScalars.str(
                tool,
                "kind",
            ) == "freeform"
        ) {
            CodeModeManual.Kind.FREEFORM
        } else {
            CodeModeManual.Kind.FUNCTION
        },
        namespace = JsonScalars.str(tool, "namespace"),
        namespaceDescription = JsonScalars.str(tool, "namespace_description"),
        inputSchemaMaxBytes = budget(tool, "input_schema_max_bytes"),
        codeModeInputSchemaMaxBytes = budget(tool, "code_mode_input_schema_max_bytes"),
        freeformSyntax = JsonScalars.str(tool, "freeform_syntax"),
    )

    /** A valid usize above the JVM's renderable string size cannot truncate any supported wire schema. */
    fun budget(input: JsonObject, key: String): Int? {
        val number = (input[key] as? JsonPrimitive)?.takeUnless { it.isString }
            ?.let(JsonScalars::str)?.toBigIntegerOrNull() ?: return null
        if (number.signum() < 0 || number.bitLength() > Long.SIZE_BITS) return null
        return number.min(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }

    fun loadable(input: JsonElement?, codeModeInputSchemaMaxBytes: Int? = null): List<CodeModeManual.NestedTool> =
        read(input, codeModeInputSchemaMaxBytes).zip((input as? JsonArray).orEmpty()).map { (tool, value) ->
            if (tool.metadata.kind == CodeModeManual.Kind.FREEFORM) {
                tool.copy(inputSchema = null, outputSchema = null)
            } else {
                tool.copy(
                    inputSchema = (value as JsonObject)["input_schema"] ?: CodeModeToolDefinitions.emptyInputSchema,
                )
            }
        }

    fun typed(input: JsonElement?, codeModeInputSchemaMaxBytes: Int? = null): List<CodeModeManual.NestedTool> =
        loadable(input, codeModeInputSchemaMaxBytes).map { tool ->
            if (tool.metadata.kind == CodeModeManual.Kind.FREEFORM) {
                tool
            } else {
                tool.copy(
                    inputSchema = CodeModeToolDefinitions.parameters(checkNotNull(tool.inputSchema)),
                )
            }
        }

    fun wire(tool: CodeModeManual.NestedTool): JsonObject = buildJsonObject {
        put("name", tool.codeModeName)
        put(
            "tool_name",
            buildJsonObject {
                put("name", tool.name)
                put("namespace", tool.metadata.namespace)
            },
        )
        put("description", tool.description)
        put("kind", if (tool.metadata.kind == CodeModeManual.Kind.FREEFORM) "freeform" else "function")
        put("input_schema", tool.inputSchema ?: JsonNull)
        put("output_schema", tool.outputSchema ?: JsonNull)
    }

    fun options(options: JsonObject?): CodeModeManual.DescriptionOptions {
        val messages = options?.get("messages") as? JsonObject
        return CodeModeManual.DescriptionOptions(
            defaultYieldTimeMs = JsonScalars.long(
                options,
                "default_yield_time_ms",
            ) ?: CodeModeManual.DEFAULT_EXEC_YIELD_TIME_MS,
            codeModeOnly = flag(options, "code_mode_only", true),
            imageDetailVisible = flag(options, "image_detail_visible", true),
            discovery = if (JsonScalars.str(options, "discovery") == "ranked_search") {
                CodeModeManual.Discovery.RANKED_SEARCH
            } else {
                CodeModeManual.Discovery.CATALOG
            },
            messages = CodeModeManual.Messages(
                execDescription = JsonScalars.str(messages, "exec_description"),
                deferredGuidance = JsonScalars.str(messages, "deferred_guidance"),
                mcpPreamble = JsonScalars.str(messages, "mcp_preamble"),
            ),
        )
    }

    fun flag(input: JsonObject?, key: String, default: Boolean): Boolean =
        (input?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.let(JsonScalars::str)
            ?.toBooleanStrictOrNull() ?: default
}
