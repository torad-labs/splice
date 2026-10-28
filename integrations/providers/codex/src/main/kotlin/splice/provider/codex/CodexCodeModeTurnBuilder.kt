// NEW: arms code mode only for eligible Codex Responses-lite turns and extracts owned results.
package splice.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.client.AgentConversationKey
import splice.core.parse.AnthropicTurnBody
import splice.core.util.JsonScalars
import splice.dialect.responses.request.ResponsesDynamicToolChanges
import splice.dialect.responses.request.ResponsesToolResultMedia
import splice.provider.codex.branch.CodeModeClearedCandidates
import splice.provider.codex.callbacks.CodeModeClientResults
import splice.provider.codex.catalog.CodeModeCatalogMessages
import splice.provider.codex.request.CodeModePreparedSurface
import splice.upstream.BuiltTurn
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.availability.CodeModeToolMode

/** [media] is the dialect's own tool_result image renderer (V4-179): the bridge renders a result's
 *  follow-ups ONCE with the same policy the ordinary path applies, persists them on the record and
 *  replays them from there — so what the model sees for a screenshot inside a script is exactly
 *  what it would see for one outside it, and in the same place. */
internal class CodexCodeModeTurnBuilder(
    private val bridge: CodexCodeModeBridge?,
    media: ResponsesToolResultMedia,
    models: Collection<String>? = null,
    private val codeModeOnly: CodeModeOnlyModels = NoCodeModeOnlyModels,
    private val originalImageModels: CodeModeOriginalImageModels = NoCodeModeOriginalImageModels,
    private val settings: CodexCodeModeSettings = CodexCodeModeSettings(),
) {
    private val catalogs = splice.provider.codex.catalog.CodeModeTurnCatalogs(settings, originalImageModels)
    private val operatorModels: Set<String> = CodexCodeModeModels.normalize(models.orEmpty())
    private val results = CodeModeClientResults(media)

    /** The provider's compaction flag is deliberately absent: the bridge rides a compaction exactly as it rides a
     *  turn, so a preparation that could read the flag could also diverge on it (see [eligible]). */
    fun prepare(
        body: AnthropicTurnBody,
        sessionId: String?,
        built: BuiltTurn,
    ): BuiltTurn {
        val manager = bridge ?: return built
        val availability = ResponsesDynamicToolChanges.availability(body.typed)
        if (!eligible(body, built, availability)) return built
        require(availability.knownDefinitions.none { it.name == CODE_MODE_TOOL_NAME }) {
            "client tool name '$CODE_MODE_TOOL_NAME' collides with splice's code-mode bridge"
        }
        require(!sessionId.isNullOrBlank()) { "code mode requires a client session id" }
        val cleared = CodeModeClearedCandidates.read(body)
        val loaded = CodeModeLoadedTools(body, availability)
        val toolPolicy = catalogs.policy(body, built, availability, requestedMode(built.meta.route.upstreamModel))
            .copy(loadedNames = loaded.names(results.blocks(body)))
        val messages = CodeModeCatalogMessages(toolPolicy.modelMessages)
        val definitions = catalogs.definitions(body, built.requestBody, availability)
        val turn = CodexCodeModeBridge.Turn(
            sessionId = sessionId,
            conversationKey = built.meta.scope.conversationKey ?: AgentConversationKey.of("", body.agentId),
            model = built.meta.route.upstreamModel,
            tools = availability.currentCatalog.map { it.name }.toSet(),
            toolResults = toolResults(body, loaded),
            rendering = CodeModeResultRendering(
                toolMedia(body).filterKeys { it !in cleared },
                legacyResults(body),
                cleared,
            ),
            catalog = splice.provider.codex.catalog.CodeModeTurnCatalog(
                messages.functions(definitions),
                toolPolicy,
                loaded.descriptions(),
                manager.mcpSearch.entries(definitions).toMap(),
            ),
        )
        val disableParallel = body.typed.toolChoice?.disableParallelToolUse == true
        return CodeModePreparedSurface.prepare(manager, body, built, turn, disableParallel)
    }

    // 2026-09-21 (cache law, QuirksConfig.compactEffort): a compaction is built EXACTLY like a turn.
    // This used to refuse compact turns, which dropped the splice_exec declaration and the guidance
    // paragraph from the compaction's request — the ONE byte-level difference from the turn before it
    // (traced 2026-09-21: prefix shared for 110 KB, the tools block, then diverged), so every
    // compaction on a 200k session re-read the whole transcript uncached (perf: cached_tokens=0 or
    // ~20k on every compact=true row, 98% on the turns around it) — 5% of a 5h budget per compaction.
    // The interceptor stays armed too: the summarizer's last message forbids tools; a cell the model
    // calls anyway takes the ordinary code-mode path (a completed local cell continues upstream, a
    // cell that reaches for client tools surfaces them as ordinary tool calls — astra, PR #169),
    // which is exactly what an ordinary tool call on a compaction already did before this change.
    private fun eligible(
        body: AnthropicTurnBody,
        built: BuiltTurn,
        availability: ResponsesDynamicToolChanges.Availability,
    ): Boolean {
        val choice = body.typed.toolChoice
        val choiceAllowsBridge = choice == null || (choice.name == null && choice.type in setOf("auto", "any"))
        return availability.knownDefinitions.isNotEmpty() &&
            choiceAllowsBridge &&
            runsCodeMode(built.meta.route.upstreamModel) &&
            isLiteRequest(built.requestBody)
    }

    /** The backend's flag is read now, each turn: see [CodeModeOnlyModels]. */
    private fun runsCodeMode(upstreamModel: String): Boolean =
        CodexCodeModeModels.eligible(upstreamModel, operatorModels) ||
            CodexCodeModeModels.eligible(upstreamModel, CodexCodeModeModels.normalize(codeModeOnly.ids()))

    private fun requestedMode(upstreamModel: String): CodeModeToolMode =
        if (CodexCodeModeModels.eligible(upstreamModel, CodexCodeModeModels.normalize(codeModeOnly.ids()))) {
            CodeModeToolMode.CODE_MODE_ONLY
        } else {
            CodeModeToolMode.CODE_MODE
        }

    private fun isLiteRequest(request: JsonObject): Boolean {
        val input = request[FIELD_INPUT] as? JsonArray ?: return false
        val first = input.firstOrNull() as? JsonObject ?: return false
        return when {
            request[FIELD_TOOLS] != null -> false
            JsonScalars.str(first, FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS -> first[FIELD_TOOLS] is JsonArray
            // Source deferral can leave no initial direct declarations in an otherwise lite request.
            else -> JsonScalars.str(first, "role") == "developer" &&
                JsonScalars.str(request, "instructions").isNullOrEmpty()
        }
    }

    /** Internal, not private: V4-178 pins the marker on the parsed result, and the interceptor the
     *  turn rides in is an opaque lambda a test cannot look inside. */
    internal fun toolResults(
        body: AnthropicTurnBody,
        loaded: CodeModeLoadedTools = CodeModeLoadedTools(body),
    ): List<CodeModeResult> = results.read(body, loaded)

    /** V4-388: each client tool's `ALL_TOOLS` description as codex's augment_tool_definition writes it —
     *  with its TypeScript declaration, so a tool the manual defers still shows the cell its arguments. */
    internal fun descriptions(body: AnthropicTurnBody): Map<String, String> = CodeModeLoadedTools(body).descriptions()

    /** V4-179: each bridge result's follow-up items, rendered once here and nowhere else. */
    internal fun toolMedia(body: AnthropicTurnBody): Map<String, List<JsonElement>> =
        results.media(body)

    /** V4-179: the same results as the daemon before this row rendered them — for replay identity
     *  against a record that daemon persisted (CodexCodeModeValidation.conflicts), never for the model. */
    internal fun legacyResults(body: AnthropicTurnBody): List<CodeModeResult> = results.legacy(body)
}

/**
 * Which upstream models are offered the runner. A model runs code mode when the BACKEND marks it
 * `tool_mode = "code_mode_only"` (V4-388, V4-441: models trained on the one-`exec` surface, which run with
 * direct tools badly: 2026-09-28, 98% direct calls and 1.6% exec) OR the operator's TOML `code_mode_models`
 * names it. That list is an addition and the only way a model the backend hides from its list reaches
 * code mode (codex-auto-review). An optional `[Nk|Nm]` context suffix on the model id is ignored.
 */
internal object CodexCodeModeModels {
    private val contextSuffix = Regex("\\[\\d+[km]]$", RegexOption.IGNORE_CASE)

    fun normalize(models: Collection<String>): Set<String> =
        models.map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet()

    fun eligible(upstreamModel: String, models: Set<String>): Boolean =
        upstreamModel.trim().lowercase().replace(contextSuffix, "") in models
}

private const val FIELD_INPUT = "input"
internal const val FIELD_TOOLS = "tools"
private const val FIELD_TYPE = "type"
internal const val TYPE_ADDITIONAL_TOOLS = "additional_tools"
