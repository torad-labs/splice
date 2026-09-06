// NEW: Claude Code's 30-second activity label, answered by the proxy (2026-09-05).
//
// Every 30 s (`kxo=30000`, the AgentSummary timer in Claude Code 2.1.257) the client forks the
// session's transcript and asks the model to "Describe your most recent action in 3-5 words using
// present tense (-ing)" — the status line's "Reading runAgent.ts". The fork carries the whole
// conversation and every tool schema, so each label costs a full-context read: on 2026-09-05 it was
// 2256 of the claudex head's rounds and 294M input tokens at a 48% cache-hit rate (the same
// sessions' real turns hit 96%), for 8-13 output tokens apiece. The label is a function of the
// transcript's last tool call, which the proxy already holds, so it is composed HERE and no
// upstream turn happens (TurnPreparation → Preparation.Local → LocalResponses). The match is the
// prompt's verbatim opening sentence: a reworded prompt in a later Claude Code rides upstream
// exactly as before, and nothing else matches it short of a user typing that sentence.
package splice.gateway.head

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.wire.AnthropicRequest
import splice.core.wire.ContentBlock.TextBlock
import splice.core.wire.ContentBlock.ToolUseBlock

internal class ActivityLabel {

    /** The label to answer with, or null when the request is not the activity side query. */
    fun labelFor(request: AnthropicRequest): String? {
        if (!isSideQuery(request)) return null
        val lastAssistant = request.messages.lastOrNull { it.role == ROLE_ASSISTANT }
        val call = lastAssistant?.content?.filterIsInstance<ToolUseBlock>()?.lastOrNull()
        return when {
            lastAssistant == null -> NO_TRANSCRIPT_LABEL
            call == null -> NO_TOOL_LABEL
            else -> describe(call)
        }
    }

    private fun isSideQuery(request: AnthropicRequest): Boolean {
        val last = request.messages.lastOrNull()?.takeIf { it.role == ROLE_USER } ?: return false
        val text = last.content.filterIsInstance<TextBlock>().joinToString("\n") { it.text }.trimStart()
        return text.startsWith(SIDE_QUERY_OPENING)
    }

    // Present tense, 3-5 words, the file or function — Claude Code's own examples for the prompt.
    private fun describe(call: ToolUseBlock): String {
        FIXED_LABELS[call.name]?.let { return it }
        FILE_TOOLS[call.name]?.let { (verb, key) -> return "$verb ${fileName(call.input, key)}" }
        return when (call.name) {
            "Bash" -> "Running ${commandHead(str(call.input, "command"))}"
            "Grep" -> "Searching for ${clip(str(call.input, "pattern")) ?: "a pattern"}"
            "Glob" -> "Finding ${clip(str(call.input, "pattern")) ?: "files"}"
            else -> generic(call.name)
        }
    }

    private fun generic(name: String): String =
        if (name.startsWith(MCP_PREFIX)) "Calling ${name.substringAfterLast("__")}" else "Using $name"

    private fun str(input: JsonObject, key: String): String? =
        (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun fileName(input: JsonObject, key: String): String =
        clip(str(input, key)?.trimEnd('/')?.substringAfterLast('/')) ?: "a file"

    /** The first real command's program and first argument — "git status", "gradlew test": past the
     *  `cd`/`export`/assignment prelude, before any `| tail` behind it. */
    private fun commandHead(command: String?): String {
        val words = command.orEmpty().split(SEGMENT_SPLIT).asSequence()
            .map { segment -> segment.trim().split(WHITESPACE).filter { it.isNotEmpty() }.dropWhile { isPrelude(it) } }
            .firstOrNull { it.isNotEmpty() && it.first() !in SHELL_PRELUDE }
            .orEmpty()
            .take(2)
            .map { it.substringAfterLast('/') }
            .filter { it.any(Char::isLetterOrDigit) }
        return clip(words.joinToString(" ")) ?: "a command"
    }

    private fun isPrelude(token: String): Boolean = token == "sudo" || token == "env" || token.contains('=')

    private fun clip(text: String?): String? =
        text?.takeIf { it.isNotBlank() }?.let { if (it.length > MAX_ARG_CHARS) it.take(MAX_ARG_CHARS) + "…" else it }
}

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"
private const val MCP_PREFIX = "mcp__"
private const val MAX_ARG_CHARS = 32
private const val SIDE_QUERY_OPENING = "Describe your most recent action in 3-5 words using present tense (-ing)."
private const val NO_TRANSCRIPT_LABEL = "Reading the request"
private const val NO_TOOL_LABEL = "Replying to the user"
private const val FILE_PATH = "file_path"
private val FILE_TOOLS = mapOf(
    "Read" to ("Reading" to FILE_PATH),
    "Edit" to ("Editing" to FILE_PATH),
    "MultiEdit" to ("Editing" to FILE_PATH),
    "NotebookEdit" to ("Editing" to "notebook_path"),
    "Write" to ("Writing" to FILE_PATH),
)
private val FIXED_LABELS = mapOf(
    "Agent" to "Delegating to a subagent",
    "Task" to "Delegating to a subagent",
    "WebFetch" to "Searching the web",
    "WebSearch" to "Searching the web",
    "TodoWrite" to "Updating the task list",
    "TaskCreate" to "Updating the task list",
    "TaskUpdate" to "Updating the task list",
    "AskUserQuestion" to "Asking the user",
    "SendMessage" to "Messaging a peer session",
)
private val SHELL_PRELUDE = setOf("cd", "pushd", "popd", "export", "set", "source", ".", "echo")
private val SEGMENT_SPLIT = Regex("&&|\\|\\||[;|\\n]")
private val WHITESPACE = Regex("\\s+")
