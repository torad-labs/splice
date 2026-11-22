// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). The stream-json lines a `claude` started by
// Claude Desktop writes, in the shapes of the Agent SDK's types (SDKControlResponse, SDKSystemMessage `init`,
// SDKUserMessageReplay, SDKPartialAssistantMessage, SDKAssistantMessage, SDKResultMessage and the `can_use_tool`
// control request), built for a session that runs on agy. Every line names the session it belongs to.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** One slash command agy offers, as Desktop lists it. */
internal data class AgyCommand(val name: String, val description: String, val hint: String)

/** What `system/init` says of the session. */
internal data class SessionLook(
    val cwd: String,
    val model: String,
    val permissionMode: String,
    val commands: List<AgyCommand>,
    val version: String,
)

/** What a turn's `result` reports. */
internal data class TurnEnd(
    val stopReason: String?,
    val error: String?,
    val durationMs: Long,
    val usage: JsonObject,
    val model: String,
    val window: Long,
)

/** Builds the lines of session [sessionId]. */
internal class DesktopFrames(internal val sessionId: String) {
    fun controlOk(requestId: String, response: JsonObject = JsonObject(emptyMap())): JsonObject =
        control(requestId) {
            put(SUBTYPE, "success")
            put("response", response)
        }

    fun controlError(requestId: String, error: String): JsonObject = control(requestId) {
        put(SUBTYPE, "error")
        put("error", error)
    }

    /** The answer to Desktop's `initialize`: agy's commands, its models, no agents, the default output style, and no
     *  MCP server of Desktop's applied (they are not passed to agy). */
    fun initialized(requestId: String, commands: List<AgyCommand>, models: JsonArray): JsonObject =
        controlOk(
            requestId,
            buildJsonObject {
                put("commands", commandsOf(commands))
                put("agents", JsonArray(emptyList()))
                put("output_style", OUTPUT_STYLE)
                putJsonArray("available_output_styles") { add(JsonPrimitive(OUTPUT_STYLE)) }
                put("models", models)
                put("account", JsonObject(emptyMap()))
                put("mcp_servers_applied", false)
            },
        )

    fun init(look: SessionLook): JsonObject = framed("system") {
        put(SUBTYPE, "init")
        put("cwd", look.cwd)
        put("model", look.model)
        put("tools", JsonArray(claudeToolNames.map(::JsonPrimitive)))
        put("mcp_servers", JsonArray(emptyList()))
        put("permissionMode", look.permissionMode)
        put("slash_commands", JsonArray(look.commands.map { JsonPrimitive(it.name) }))
        put("output_style", OUTPUT_STYLE)
        put("skills", JsonArray(emptyList()))
        put("plugins", JsonArray(emptyList()))
        put("agents", JsonArray(emptyList()))
        put("claude_code_version", look.version)
        put("apiKeySource", "none")
    }

    /** Desktop's own [line], said back as replayed (`--replay-user-messages`), under [uuid]. */
    fun replay(line: JsonObject, uuid: String): JsonObject = JsonObject(
        line + mapOf(
            "uuid" to JsonPrimitive(uuid),
            SESSION_ID_KEY to JsonPrimitive(sessionId),
            "isReplay" to JsonPrimitive(true),
        ),
    )

    fun streamEvent(event: JsonObject): JsonObject = framed("stream_event") {
        put("event", event)
        put("parent_tool_use_id", JsonNull)
    }

    fun assistant(message: JsonObject, uuid: String): JsonObject = framed("assistant", uuid) {
        put("message", message)
        put("parent_tool_use_id", JsonNull)
    }

    /** The `user` line carrying a tool's result, [content] being the result blocks. */
    fun toolResult(content: JsonArray, uuid: String): JsonObject = framed("user", uuid) {
        putJsonObject("message") {
            put("role", "user")
            put("content", content)
        }
        put("parent_tool_use_id", JsonNull)
    }

    fun result(end: TurnEnd): JsonObject = framed("result") {
        put(SUBTYPE, "success")
        put("is_error", end.error != null)
        put("duration_ms", end.durationMs)
        put("duration_api_ms", end.durationMs)
        put("num_turns", 1)
        put("result", end.error.orEmpty())
        put("stop_reason", end.stopReason)
        put("total_cost_usd", 0)
        put("usage", end.usage)
        putJsonObject("modelUsage") {
            putJsonObject(end.model) {
                put("inputTokens", end.usage["input_tokens"] ?: JsonPrimitive(0))
                put("outputTokens", end.usage["output_tokens"] ?: JsonPrimitive(0))
                put("cacheReadInputTokens", end.usage["cache_read_input_tokens"] ?: JsonPrimitive(0))
                put("cacheCreationInputTokens", end.usage["cache_creation_input_tokens"] ?: JsonPrimitive(0))
                put("webSearchRequests", 0)
                put("costUSD", 0)
                put("contextWindow", end.window)
            }
        }
        put("permission_denials", JsonArray(emptyList()))
        if (end.error != null) putJsonArray("errors") { add(JsonPrimitive(end.error)) }
    }

    /** Desktop asked whether tool [toolUseId] may run: [card] names it and its input. */
    fun canUseTool(requestId: String, toolUseId: String, card: ToolCard): JsonObject = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request") {
            put(SUBTYPE, "can_use_tool")
            put("tool_name", card.name)
            put("input", card.input)
            put("tool_use_id", toolUseId)
            put("title", card.title)
            put("display_name", card.name)
        }
    }

    /** Desktop's question [requestId] is no longer asked. */
    fun cancelled(requestId: String): JsonObject = buildJsonObject {
        put("type", "control_cancel_request")
        put("request_id", requestId)
    }

    private inline fun control(
        requestId: String,
        crossinline body: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
            body()
            put("request_id", requestId)
        }
    }

    private inline fun framed(
        type: String,
        uuid: String = UUID.randomUUID().toString(),
        crossinline body: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", type)
        body()
        put("uuid", uuid)
        put(FRAME_SESSION_ID, sessionId)
    }

    private fun commandsOf(commands: List<AgyCommand>): JsonArray = buildJsonArray {
        commands.forEach { command ->
            add(
                buildJsonObject {
                    put("name", command.name)
                    put("description", command.description)
                    put("argumentHint", command.hint)
                },
            )
        }
    }
}

/** System task lifecycle frames that open and close Claude Desktop's subagent panes. */
internal class TaskFrames(private val sessionId: String) {
    fun started(id: String, subagent: SubagentCall): JsonObject = buildJsonObject {
        put("type", "system")
        put(SUBTYPE, "task_started")
        put("task_id", id)
        put(FRAME_TOOL_USE_ID, id)
        put("description", subagent.description)
        if (subagent.subagentType.isNotEmpty()) put("subagent_type", subagent.subagentType)
        put("task_type", "local_agent")
        put("is_backgrounded", false)
        put("uuid", UUID.randomUUID().toString())
        put(FRAME_SESSION_ID, sessionId)
    }

    fun notification(id: String, status: String, summary: String): JsonObject = buildJsonObject {
        put("type", "system")
        put(SUBTYPE, "task_notification")
        put("task_id", id)
        put(FRAME_TOOL_USE_ID, id)
        put("status", status)
        put("summary", summary)
        put("uuid", UUID.randomUUID().toString())
        put(FRAME_SESSION_ID, sessionId)
    }
}

private const val OUTPUT_STYLE = "default"
private const val FRAME_SESSION_ID = "session_id"
private const val FRAME_TOOL_USE_ID = "tool_use_id"

/** The member that says which kind of control message or system message a line is. */
private const val SUBTYPE = "subtype"
