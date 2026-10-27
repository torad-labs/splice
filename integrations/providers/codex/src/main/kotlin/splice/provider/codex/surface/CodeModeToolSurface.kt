package splice.provider.codex.surface

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.dialect.responses.ResponsesFunctionNamespace
import splice.provider.codex.CODE_MODE_FIELD_INPUT
import splice.provider.codex.CODE_MODE_FIELD_TYPE
import splice.provider.codex.CODE_MODE_TOOL_NAME
import splice.provider.codex.CodeModeToolPolicy
import splice.provider.codex.FIELD_TOOLS
import splice.provider.codex.TYPE_ADDITIONAL_TOOLS
import splice.provider.codex.catalog.CodeModeCatalogMessages
import splice.provider.codex.catalog.CodeModeWaitTool
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeToolDefinitions
import splice.upstream.codemode.CodeModeToolSearch
import java.util.concurrent.ConcurrentHashMap

/** The declaration and reachable manual share one per-wire collision log. */
internal class CodeModeToolSurface(private val log: LogSink) {
    private val namespace = ResponsesFunctionNamespace()
    private val announcedCollisions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** V4-388: client functions live inside exec, including definitions added in source history.
     *  [clientTools] is the initial catalog, keeping its historical manual stable. Later schemas
     *  stay documented at their own boundary, without becoming direct upstream function handlers.
     *  The provider captures current nested membership separately and retains native deferral. */
    fun injectTool(
        request: JsonObject,
        clientTools: Set<String>,
        policy: CodeModeToolPolicy = CodeModeToolPolicy(),
    ): JsonObject {
        val input = request[CODE_MODE_FIELD_INPUT] as? JsonArray ?: return request
        val leading = (input.firstOrNull() as? JsonObject)
            ?.takeIf { string(it, CODE_MODE_FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS }
        val prefix = declaration(
            leading ?: emptyInitialDeclaration(),
            reachable(clientTools) - policy.loadedNames,
            policy,
        )
        val history = if (leading == null) input.toList() else input.drop(1)
        val rebuilt = listOf(prefix) + history.map { element ->
            val item = (element as? JsonObject)?.takeIf { string(it, CODE_MODE_FIELD_TYPE) == TYPE_ADDITIONAL_TOOLS }
            item?.let { historicalDeclaration(it, policy) } ?: element
        }
        return JsonObject(request + (CODE_MODE_FIELD_INPUT to JsonArray(rebuilt)))
    }

    private fun historicalDeclaration(item: JsonObject, policy: CodeModeToolPolicy): JsonObject {
        val tools = item[FIELD_TOOLS] as? JsonArray ?: return item
        val names = CodeModeToolDefinitions.collect(tools).map { it.codeModeName }.toSet()
        return declaration(item, reachable(names), policy)
    }

    private fun declaration(
        item: JsonObject,
        documented: Set<String>,
        policy: CodeModeToolPolicy,
    ): JsonObject {
        val tools = item[FIELD_TOOLS] as? JsonArray
        require(tools != null) { "code mode requires a Responses lite additional_tools array" }
        val messages = CodeModeCatalogMessages(policy.modelMessages)
        val enabled = CodeModeToolDefinitions.collect(tools).filter {
            it.codeModeName in documented || it.codeModeName == CodeModeToolSearch.FUNCTION_NAME
        }.map { CodeModeManual.withInputBudget(it, policy.inputSchemaMaxBytes) }
        val nested = messages.namespacePrefixes.prompt(messages.functions(enabled))
        val deferred = (documented - nested.map { it.codeModeName }.toSet()).isNotEmpty()
        val beside = namespace.members(tools).filterNot {
            string(it as? JsonObject, CODE_MODE_FIELD_TYPE) in setOf("function", "custom", "namespace", "tool_search")
        }
        val exec = execTool(
            CodeModeManual.description(
                nested,
                deferred,
                CodeModeManual.DescriptionOptions(
                    defaultYieldTimeMs = policy.defaultExecYieldTimeMs,
                    imageDetailVisible = policy.imageDetailVisible,
                    messages = messages.exec,
                ),
            ),
        )
        // Both native handlers stay advertised even when every client tool is deferred or withdrawn.
        val surface = namespace.group(JsonArray(listOf(exec, CodeModeWaitTool.declaration(messages, log)) + beside))
        return JsonObject(item + (FIELD_TOOLS to surface))
    }

    private fun emptyInitialDeclaration(): JsonObject = buildJsonObject {
        put(CODE_MODE_FIELD_TYPE, TYPE_ADDITIONAL_TOOLS)
        put("role", "developer")
        put(FIELD_TOOLS, JsonArray(emptyList()))
    }

    /** The worker and manual use the same normalized identifiers and collision rule. */
    private fun reachable(clientTools: Set<String>): Set<String> {
        val kept = CodeModeManual.nestedNames(clientTools).toSet()
        (clientTools - kept).filter { announcedCollisions.add(it) }.forEach { name ->
            val global = CodeModeManual.identifier(name)
            log("[code-mode] skipping client tool '$name': its code-mode name $global is taken")
        }
        return kept
    }

    /** codex-rs execute_spec.rs create_code_mode_tool: a freeform tool constrained by the exec grammar. */
    private fun execTool(description: String): JsonObject = buildJsonObject {
        put(CODE_MODE_FIELD_TYPE, "custom")
        put("name", CODE_MODE_TOOL_NAME)
        put("description", description)
        put(
            "format",
            buildJsonObject {
                put(CODE_MODE_FIELD_TYPE, "grammar")
                put("syntax", "lark")
                put("definition", CodeModeManual.GRAMMAR)
            },
        )
    }

    private fun string(item: JsonObject?, key: String): String = JsonScalars.str(item, key).orEmpty()
}
