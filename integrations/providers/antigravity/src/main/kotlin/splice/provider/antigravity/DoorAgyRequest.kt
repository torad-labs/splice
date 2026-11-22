// NEW: desktop-antigravity (door chat). Parses an incoming Anthropic Messages JSON request for
// AntigravityDoorEngine and enforces the Google-terms guardrail keeping Claude Code CLI loops off
// the HTTP door (only Claude Desktop Chat turns ride through here).
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.security.MessageDigest
import java.util.UUID

/** Parsed view of an Anthropic `/v1/messages` request handled by [AntigravityDoorEngine]. */
internal data class DoorAgyAsk(
    val model: String,
    val effort: String?,
    val tools: List<AnthropicToolDef>,
    val messages: List<JsonObject>,
    val conversationKey: String,
    val trailingToolResults: List<BridgedToolResult>,
    val latestUserContent: JsonElement?,
) {
    val toolNames: List<String> get() = tools.map { it.name }

    /** Builds a lossy transcript prefix from messages before the latest user turn when opening a fresh session. */
    fun historyPrefix(): String? {
        if (messages.size <= 1) return null
        val earlier = messages.dropLast(1).mapNotNull(DoorAgyRequestParser::formatMessage)
        return earlier.takeIf { it.isNotEmpty() }?.let { HISTORY_PREFIX + it.joinToString("\n\n") }
    }
}

/** Guardrail distinguishing Claude Desktop Chat from Claude Code CLI agent loops (desktop-antigravity.md). */
public object DoorAgyGuard {
    /** True when the HTTP request comes from Claude Code CLI (which must never drive agy over HTTP). */
    public fun isClaudeCodeRequest(userAgent: String?, xApp: String?, request: JsonObject): Boolean {
        if (userAgent != null && userAgent.trimStart().startsWith(CLAUDE_CLI_UA_PREFIX, ignoreCase = true)) {
            return true
        }
        if (xApp != null && xApp.trim().equals(CLAUDE_CLI_X_APP, ignoreCase = true)) {
            return true
        }
        val names = (request["tools"] as? JsonArray).orEmpty()
            .mapNotNull { JsonScalars.str(it as? JsonObject, FIELD_NAME) }
            .toSet()
        return "Bash" in names && "Read" in names && ("Edit" in names || "Write" in names)
    }
}

internal object DoorAgyRequestParser {
    fun parse(request: JsonObject, upstreamModel: String, sessionHint: String? = null): DoorAgyAsk {
        val messages = (request["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val tools = parseTools(request["tools"] as? JsonArray)
        val effort = parseEffort(request)
        val last = messages.lastOrNull()
        val lastIsUser = JsonScalars.str(last, FIELD_ROLE) == ROLE_USER
        val trailingToolResults = if (lastIsUser) extractToolResults(last?.get(FIELD_CONTENT)) else emptyList()
        val latestUserContent = if (lastIsUser) stripToolResults(last?.get(FIELD_CONTENT)) else null
        val conversationKey = deriveConversationKey(messages, sessionHint)
        return DoorAgyAsk(
            model = upstreamModel,
            effort = effort,
            tools = tools,
            messages = messages,
            conversationKey = conversationKey,
            trailingToolResults = trailingToolResults,
            latestUserContent = latestUserContent,
        )
    }

    internal fun formatMessage(message: JsonObject): String? {
        val role = JsonScalars.str(message, FIELD_ROLE) ?: return null
        val label = if (role == ROLE_ASSISTANT) "Assistant" else "User"
        val body = formatContent(message[FIELD_CONTENT])
        return body.takeIf { it.isNotEmpty() }?.let { "$label: $it" }
    }

    private fun parseTools(raw: JsonArray?): List<AnthropicToolDef> = raw.orEmpty().mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val name = JsonScalars.str(obj, FIELD_NAME)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val schema = obj["input_schema"] as? JsonObject ?: return@mapNotNull null
        val description = JsonScalars.str(obj, "description").orEmpty()
        AnthropicToolDef(name, description, schema)
    }

    private fun parseEffort(request: JsonObject): String? {
        val explicit = JsonScalars.str(request["output_config"] as? JsonObject, "effort")
            ?: JsonScalars.str(request, "effort")
        if (!explicit.isNullOrBlank()) return normalizeEffort(explicit)
        val budget = JsonScalars.long(request["thinking"] as? JsonObject, "budget_tokens") ?: return null
        return when {
            budget <= LOW_BUDGET_MAX -> "low"
            budget <= MEDIUM_BUDGET_MAX -> "medium"
            budget <= HIGH_BUDGET_MAX -> "high"
            else -> "xhigh"
        }
    }

    private fun normalizeEffort(raw: String): String = when (raw.lowercase()) {
        "max" -> "xhigh"
        else -> raw.lowercase()
    }

    private fun extractToolResults(content: JsonElement?): List<BridgedToolResult> {
        val arr = content as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (JsonScalars.str(obj, FIELD_TYPE) != BLOCK_TOOL_RESULT) return@mapNotNull null
            val id = JsonScalars.str(obj, "tool_use_id") ?: return@mapNotNull null
            val text = flattenToolResultContent(obj[FIELD_CONTENT])
            val isError = JsonScalars.str(obj, "is_error") == "true"
            BridgedToolResult(id, text, isError)
        }
    }

    private fun flattenToolResultContent(content: JsonElement?): String = when (content) {
        null -> ""
        is JsonPrimitive -> JsonScalars.str(content).orEmpty()
        is JsonArray -> content.mapNotNull { item ->
            val obj = item as? JsonObject
            if (obj != null && JsonScalars.str(obj, FIELD_TYPE) == BLOCK_TEXT) {
                JsonScalars.str(obj, BLOCK_TEXT)
            } else {
                JsonWire.string(item)
            }
        }.joinToString("\n")
        is JsonObject -> JsonWire.string(content)
    }

    private fun stripToolResults(content: JsonElement?): JsonElement? = when (content) {
        null -> null
        is JsonPrimitive -> content
        is JsonArray -> {
            val kept = content.filter { item ->
                val obj = item as? JsonObject
                obj == null || JsonScalars.str(obj, FIELD_TYPE) != BLOCK_TOOL_RESULT
            }
            if (kept.isEmpty()) null else buildJsonArray { kept.forEach(::add) }
        }
        is JsonObject -> content
    }

    private fun formatContent(content: JsonElement?): String = when (content) {
        null -> ""
        is JsonPrimitive -> JsonScalars.str(content)?.trim().orEmpty()
        is JsonArray -> content.mapNotNull { formatBlock(it as? JsonObject) }.joinToString("\n")
        is JsonObject -> ""
    }

    private fun formatBlock(block: JsonObject?): String? {
        if (block == null) return null
        return when (JsonScalars.str(block, FIELD_TYPE)) {
            BLOCK_TEXT -> JsonScalars.str(block, BLOCK_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
            BLOCK_TOOL_USE -> {
                val name = JsonScalars.str(block, FIELD_NAME).orEmpty()
                val input = block["input"] as? JsonObject
                if (name.isEmpty()) null else "[Tool $name: ${input?.let(JsonWire::string) ?: "{}"}]"
            }
            BLOCK_TOOL_RESULT -> {
                val text = flattenToolResultContent(block[FIELD_CONTENT]).trim()
                text.takeIf { it.isNotEmpty() }?.let { "[Tool result: ${it.take(MAX_RESULT_CHARS)}]" }
            }
            else -> null
        }
    }

    private fun deriveConversationKey(messages: List<JsonObject>, sessionHint: String?): String {
        val firstUser = messages.firstOrNull { JsonScalars.str(it, FIELD_ROLE) == ROLE_USER }
        val seed = firstUser?.let { formatContent(it[FIELD_CONTENT]) }.orEmpty()
        val combined = listOfNotNull(sessionHint?.takeIf { it.isNotBlank() }, seed.takeIf { it.isNotBlank() })
            .joinToString("|")
        if (combined.isEmpty()) return "ephemeral-${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(Charsets.UTF_8))
        return digest.take(KEY_HASH_BYTES).joinToString("") { "%02x".format(it) }
    }
}

private const val CLAUDE_CLI_UA_PREFIX = "claude-cli/"
private const val CLAUDE_CLI_X_APP = "cli"
private const val FIELD_ROLE = "role"
private const val FIELD_CONTENT = "content"
private const val FIELD_TYPE = "type"
private const val FIELD_NAME = "name"
private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val BLOCK_TEXT = "text"
private const val BLOCK_TOOL_USE = "tool_use"
private const val BLOCK_TOOL_RESULT = "tool_result"
private const val HISTORY_PREFIX = "Conversation so far:\n\n"
private const val KEY_HASH_BYTES = 16
private const val MAX_RESULT_CHARS = 2_000
private const val LOW_BUDGET_MAX = 4_096L
private const val MEDIUM_BUDGET_MAX = 16_384L
private const val HIGH_BUDGET_MAX = 32_768L
