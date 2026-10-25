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
import splice.provider.codex.CodeModeWaitTool
import splice.upstream.codemode.CodeModeManual
import java.util.concurrent.ConcurrentHashMap

/** The declaration and reachable manual share one per-wire collision log. */
internal class CodeModeToolSurface(private val log: LogSink) {
    private val namespace = ResponsesFunctionNamespace()
    private val announcedCollisions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** V4-388: codex's code_mode_only surface (codex-rs core/src/tools/spec_plan.rs
     *  is_hidden_by_code_mode_only): every client function tool leaves the top level and is rendered
     *  into `exec`'s manual, and tool_search leaves with them — codex's own test sends exec, wait,
     *  request_user_input and web_search with deferred tools on (core/tests/suite/code_mode.rs:2805-2820).
     *  [clientTools] is the whole client catalog, so a tool the deferred surface withheld gets the
     *  manual's deferred note and is found and called through `ALL_TOOLS` and `tools`. The lite list
     *  arrives grouped into the `functions` namespace and leaves grouped the same way (V4-390): exec
     *  rides inside it, as codex's does. */
    fun injectTool(request: JsonObject, clientTools: Set<String>): JsonObject {
        val input = request[CODE_MODE_FIELD_INPUT] as? JsonArray ?: return request
        val index = input.indexOfFirst { string(it as? JsonObject, CODE_MODE_FIELD_TYPE) == "additional_tools" }
        val item = input.getOrNull(index) as? JsonObject
        val tools = item?.get("tools") as? JsonArray
        require(item != null && tools != null) { "code mode requires the Responses lite additional_tools item" }
        val (functions, hosted) = namespace.members(tools).partition {
            string(it as? JsonObject, CODE_MODE_FIELD_TYPE) == "function"
        }
        val reachable = reachable(clientTools)
        val nested = functions.mapNotNull { it as? JsonObject }.map { tool ->
            CodeModeManual.NestedTool(string(tool, "name"), string(tool, "description"), tool["parameters"])
        }.filter { it.name in reachable }
        val deferred = (reachable - nested.map(CodeModeManual.NestedTool::name).toSet()).isNotEmpty()
        val beside = hosted.filterNot { string(it as? JsonObject, CODE_MODE_FIELD_TYPE) == "tool_search" }
        val exec = execTool(CodeModeManual.description(nested, deferred))
        // Codex registers both native handlers together (spec_plan.rs:932-936); yielded cells
        // must retain an advertised wait tool even though client tools live inside exec.
        val surface = namespace.group(JsonArray(listOf(exec, CodeModeWaitTool.declaration()) + beside))
        val replaced = JsonObject(item + ("tools" to surface))
        val rebuilt = input.mapIndexed { position, element -> if (position == index) replaced else element }
        return JsonObject(request + (CODE_MODE_FIELD_INPUT to JsonArray(rebuilt)))
    }

    /** The client tools `tools.<Name>` can reach: the worker dedupes the same whole catalog with the
     *  same rule, so the manual never documents a global the cell binds to another tool. A tool whose
     *  normalized name another took is logged once, as codex warns (core/src/tools/spec_plan.rs). */
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
                put(FIELD_TYPE, "grammar")
                put("syntax", "lark")
                put("definition", CodeModeManual.GRAMMAR)
            },
        )
    }

    private fun string(item: JsonObject?, key: String): String = JsonScalars.str(item, key).orEmpty()
}
