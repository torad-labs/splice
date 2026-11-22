// NEW: desktop-antigravity (door chat). Parses an incoming Anthropic Messages JSON request for
// AntigravityDoorEngine and enforces the Google-terms guardrail keeping Claude Code CLI loops off
// the HTTP door (only Claude Desktop Chat turns ride through here).
package splice.provider.antigravity.door

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.security.MessageDigest
import java.util.UUID

/** Message count and opening prompt prefix (system/Project instructions + prior history) for [DoorAgyAsk]. */
internal data class DoorAgyTranscript(
    val messageCount: Int,
    val openingPrefix: String?,
)

/** Parsed view of an Anthropic `/v1/messages` request handled by [AntigravityDoorEngine]. */
internal data class DoorAgyAsk(
    val model: String,
    val effort: String?,
    val tools: List<AnthropicToolDef>,
    val transcript: DoorAgyTranscript,
    val conversationKey: String,
    val trailingToolResults: List<BridgedToolResult>,
    val latestUserContent: JsonElement?,
) {
    val toolNames: List<String> get() = tools.map { it.name }
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
            .mapNotNull { JsonScalars.str(it as? JsonObject, ASK_NAME) }
            .toSet()
        return "Bash" in names && "Read" in names && ("Edit" in names || "Write" in names)
    }
}

internal object DoorAgyRequestParser {
    fun parse(request: JsonObject, upstreamModel: String, sessionHint: String? = null): DoorAgyAsk {
        val messages = (request["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val systemPrompt = parseSystemPrompt(request["system"])
        val tools = parseTools(request["tools"] as? JsonArray)
        val effort = parseEffort(request)
        val last = messages.lastOrNull()
        val lastIsUser = JsonScalars.str(last, ASK_ROLE) == ASK_USER
        val trailingToolResults = if (lastIsUser) extractToolResults(last?.get(ASK_CONTENT)) else emptyList()
        val latestUserContent = if (lastIsUser) stripToolResults(last?.get(ASK_CONTENT)) else null
        val conversationKey = deriveConversationKey(messages, sessionHint)
        return DoorAgyAsk(
            model = upstreamModel,
            effort = effort,
            tools = tools,
            transcript = DoorAgyTranscript(messages.size, buildOpeningPrefix(systemPrompt, messages)),
            conversationKey = conversationKey,
            trailingToolResults = trailingToolResults,
            latestUserContent = latestUserContent,
        )
    }

    private fun buildOpeningPrefix(systemPrompt: String?, messages: List<JsonObject>): String? {
        val parts = mutableListOf<String>()
        systemPrompt?.takeIf { it.isNotBlank() }?.let { parts += SYSTEM_PREFIX + it }
        if (messages.size > 1) {
            val earlier = messages.dropLast(1).mapNotNull(::formatMessage)
            if (earlier.isNotEmpty()) parts += HISTORY_PREFIX + earlier.joinToString("\n\n")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun parseSystemPrompt(raw: JsonElement?): String? = when (raw) {
        is JsonPrimitive -> JsonScalars.str(raw)?.trim()?.takeIf { it.isNotEmpty() }
        is JsonArray -> raw.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (JsonScalars.str(obj, ASK_TYPE) == ASK_TEXT) JsonScalars.str(obj, ASK_TEXT)?.trim() else null
        }.filter { it.isNotEmpty() }.joinToString("\n\n").takeIf { it.isNotEmpty() }
        else -> null
    }

    internal fun formatMessage(message: JsonObject): String? {
        val role = JsonScalars.str(message, ASK_ROLE) ?: return null
        val label = if (role == ASK_ASSISTANT) "Assistant" else "User"
        val body = formatContent(message[ASK_CONTENT])
        return body.takeIf { it.isNotEmpty() }?.let { "$label: $it" }
    }

    private fun parseTools(raw: JsonArray?): List<AnthropicToolDef> = raw.orEmpty().mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val name = JsonScalars.str(obj, ASK_NAME)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
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
            if (JsonScalars.str(obj, ASK_TYPE) != BLOCK_TOOL_RESULT) return@mapNotNull null
            val id = JsonScalars.str(obj, "tool_use_id") ?: return@mapNotNull null
            val text = flattenToolResultContent(obj[ASK_CONTENT])
            val isError = JsonScalars.str(obj, "is_error") == "true"
            BridgedToolResult(id, text, isError)
        }
    }

    private fun flattenToolResultContent(content: JsonElement?): String = when (content) {
        null -> ""
        is JsonPrimitive -> JsonScalars.str(content).orEmpty()
        is JsonArray -> content.mapNotNull { item ->
            val obj = item as? JsonObject
            if (obj != null && JsonScalars.str(obj, ASK_TYPE) == ASK_TEXT) {
                JsonScalars.str(obj, ASK_TEXT)
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
                obj == null || JsonScalars.str(obj, ASK_TYPE) != BLOCK_TOOL_RESULT
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
        return when (JsonScalars.str(block, ASK_TYPE)) {
            ASK_TEXT -> JsonScalars.str(block, ASK_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
            BLOCK_TOOL_USE -> {
                val name = JsonScalars.str(block, ASK_NAME).orEmpty()
                val input = block["input"] as? JsonObject
                if (name.isEmpty()) null else "[Tool $name: ${input?.let(JsonWire::string) ?: "{}"}]"
            }
            BLOCK_TOOL_RESULT -> {
                val text = flattenToolResultContent(block[ASK_CONTENT]).trim()
                text.takeIf { it.isNotEmpty() }?.let { "[Tool result: ${it.take(MAX_RESULT_CHARS)}]" }
            }
            else -> null
        }
    }

    private fun deriveConversationKey(messages: List<JsonObject>, sessionHint: String?): String {
        val firstUser = messages.firstOrNull { JsonScalars.str(it, ASK_ROLE) == ASK_USER }
        val seed = firstUser?.let { formatContent(it[ASK_CONTENT]) }.orEmpty()
        val combined = listOfNotNull(sessionHint?.takeIf { it.isNotBlank() }, seed.takeIf { it.isNotBlank() })
            .joinToString("|")
        if (combined.isEmpty()) return "ephemeral-${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(Charsets.UTF_8))
        return digest.take(KEY_HASH_BYTES).joinToString("") { "%02x".format(it) }
    }
}

private const val CLAUDE_CLI_UA_PREFIX = "claude-cli/"
private const val CLAUDE_CLI_X_APP = "cli"
private const val ASK_ROLE = "role"
private const val ASK_CONTENT = "content"
private const val ASK_TYPE = "type"
private const val ASK_NAME = "name"
private const val ASK_USER = "user"
private const val ASK_ASSISTANT = "assistant"
private const val ASK_TEXT = "text"
private const val BLOCK_TOOL_USE = "tool_use"
private const val BLOCK_TOOL_RESULT = "tool_result"
private const val SYSTEM_PREFIX = "System instructions:\n"
private const val HISTORY_PREFIX = "Conversation so far:\n\n"

// why: 128-bit prefix of SHA-256 matches prompt_cache_key's 32-hex-char conversation key width.
private const val KEY_HASH_BYTES = 16

// why: caps replayed historical tool_result snippets when re-seeding a fresh agy session after eviction.
private const val MAX_RESULT_CHARS = 2_000

// why: Anthropic thinking budget_tokens thresholds mapping Desktop's thinking picker to agy effort levels.
private const val LOW_BUDGET_MAX = 4_096L

// why: medium thinking budget upper bound before escalating agy effort to high.
private const val MEDIUM_BUDGET_MAX = 16_384L

// why: high thinking budget upper bound before escalating agy effort to xhigh.
private const val HIGH_BUDGET_MAX = 32_768L
