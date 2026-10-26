package splice.provider.codex.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.parse.AnthropicTurnBody
import splice.core.util.JsonScalars
import splice.provider.codex.CODE_MODE_FIELD_INPUT
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CodeModeOriginalImageModels
import splice.provider.codex.CodeModeToolPolicy
import splice.provider.codex.CodexCodeModeModels
import splice.provider.codex.CodexCodeModeSettings
import splice.provider.codex.FIELD_TOOLS
import splice.provider.codex.TYPE_ADDITIONAL_TOOLS
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeToolDefinitions
import splice.upstream.codemode.CodeModeToolSearch

/** One current tool catalog and its model facts are captured before starting an execution. */
internal class CodeModeTurnCatalogs(
    private val settings: CodexCodeModeSettings,
    private val originalImageModels: CodeModeOriginalImageModels,
) {
    fun definitions(body: AnthropicTurnBody, request: JsonObject): List<CodeModeManual.NestedTool> {
        val additional = (request[CODE_MODE_FIELD_INPUT] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { JsonScalars.str(it, CODE_MODE_FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS }
        val projected = CodeModeToolDefinitions.collect((additional?.get(FIELD_TOOLS) as? JsonArray).orEmpty())
        val raw = body.typed.tools.map { tool ->
            projected.firstOrNull { it.codeModeName == tool.name } ?: CodeModeManual.NestedTool(
                tool.name,
                tool.description.orEmpty(),
                CodeModeToolDefinitions.parameters(tool.inputSchema ?: CodeModeToolDefinitions.emptyInputSchema),
            )
        }
        val search = projected.filter { it.codeModeName == CodeModeToolSearch.FUNCTION_NAME }
        return (raw + search).map {
            it.copy(
                metadata = it.metadata.copy(
                    codeModeInputSchemaMaxBytes = settings.toolInputSchemaMaxBytes,
                ),
            )
        }
    }

    fun policy(body: AnthropicTurnBody, built: BuiltTurn): CodeModeToolPolicy {
        val request = built.requestBody
        val input = (request[CODE_MODE_FIELD_INPUT] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val additional = input.firstOrNull { JsonScalars.str(it, CODE_MODE_FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS }
        val eager = CodeModeToolDefinitions.collect((additional?.get(FIELD_TOOLS) as? JsonArray).orEmpty())
            .map { it.codeModeName }.toSet()
        val metadata = settings.modelCatalog.catalog().entries.firstOrNull { (name, _) ->
            CodexCodeModeModels.eligible(built.meta.route.upstreamModel, CodexCodeModeModels.normalize(listOf(name)))
        }?.value
        val supportsOriginal = metadata?.supportsOriginal ?: CodexCodeModeModels.eligible(
            built.meta.route.upstreamModel,
            CodexCodeModeModels.normalize(originalImageModels.ids()),
        )
        val unifiedImageBudget = settings.unifiedImageBudget && (metadata?.useResponsesLite == true || supportsOriginal)
        return CodeModeToolPolicy(
            deferredNames = body.typed.tools.map { it.name }.toSet() - eager,
            supportsOriginal = supportsOriginal,
            toolDescriptionFirst = settings.toolDescriptionFirst,
            showCellOverhead = settings.showCellOverhead,
            inputSchemaMaxBytes = settings.toolInputSchemaMaxBytes,
            presentation = CodeModePresentation(
                defaultExecYieldTimeMs = settings.defaultExecYieldTimeMs,
                modelMessages = metadata?.messages,
                imageDetailVisible = !unifiedImageBudget,
            ),
        )
    }
}
