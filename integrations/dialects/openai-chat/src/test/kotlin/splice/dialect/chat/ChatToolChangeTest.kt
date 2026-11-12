// Goal: on a Chat Completions head, when Claude Code adds a tool mid-conversation (a `tool_addition` in a system
// message) or removes one (a `tool_removal`), the model can call exactly the tools the conversation holds now. A Chat
// request carries no such block, so its tool list carries each change: an added tool rides in the list with the schema
// it was last given, a removed one leaves it until something offers it again, and the conversation itself reads as if
// the change blocks were never there. Chat has no deferred loading, so the list keeps every tool the client declares,
// and a conversation that changes nothing sends the client's tools exactly as before.
//
// Oct 27: the Chat dialect turned each tool_addition into "[tool_addition block omitted by ... proxy: unsupported on
// this backend]" and kept the tool list as the client first sent it, so claude-grok, which runs on this dialect, could
// never call a tool an MCP server brought mid-conversation and kept being offered the ones it took away.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import java.util.function.Supplier
import kotlin.random.Random

private const val CHANGE_RUNS = 1_000

/** Claude Code's own tools and MCP tools offered from the start, then MCP tools a server brings later. */
private val DECLARED = listOf(
    "Bash",
    "ToolSearch",
    "Read",
    "SendMessage",
    "TaskStop",
    "mcp__exa__web_search_exa",
    "mcp__github__get_issue",
)
private val BROUGHT = listOf(
    "mcp__claude_ai_Claude_Docs__batch",
    "mcp__claude_ai_Claude_Docs__guide",
    "mcp__claude_ai_Claude_Docs__update",
)
private val KEPT = setOf("Bash", "ToolSearch")

/** One generated request, the tools its conversation holds at its end (each with its schema's property names), the
 *  same request with every tool_addition and tool_removal block taken out, and whether it changed any tool at all. */
private data class ChatChangeCase(
    val request: JsonObject,
    val current: Map<String, Set<String>>,
    val stripped: JsonObject,
    val changes: Boolean,
)

/** A conversation that adds, removes and loads tools between rounds of work. Every definition gets its own property
 *  name, so a stale schema shows as a different set. */
private class ChatChangingConversation(private val random: Random) {
    private var version = 0
    private var calls = 0
    private var changes = false
    private val known = linkedMapOf<String, Set<String>>()
    private val current = linkedMapOf<String, Set<String>>()
    private val messages = mutableListOf<JsonObject>()

    fun case(): ChatChangeCase {
        val tools = DECLARED.filter { it in KEPT || random.nextInt(4) > 0 }.map { name ->
            val props = setOf("v${version++}")
            known[name] = props
            current[name] = props
            declaration(name, props, deferred = name !in KEPT && random.nextBoolean())
        }
        messages += chatTurn("user", JsonPrimitive("finish the review"))
        repeat(random.nextInt(9)) {
            when (random.nextInt(5)) {
                0 -> change("tool_addition", "These tools are now available.", ::added)
                1 -> change("tool_removal", "These tools are no longer available.", ::removed)
                2 -> search()
                3 -> call("Bash", JsonPrimitive("done"))
                else -> {
                    messages += chatTurn("assistant", JsonPrimitive("working on it"))
                    messages += chatTurn("user", JsonPrimitive("go on"))
                }
            }
        }
        messages += chatTurn("user", JsonPrimitive("carry on"))
        return ChatChangeCase(
            chatRequest(tools, messages),
            current.toMap(),
            chatRequest(tools, messages.map(::withoutChangeBlocks)),
            changes,
        )
    }

    private fun change(type: String, words: String, block: (String) -> JsonObject) {
        changes = true
        val pool = if (type == "tool_addition") BROUGHT + known.keys.take(2) else known.keys + "mcp__gone__tool"
        val blocks = pool.shuffled(random).take(random.nextInt(1, 4)).map { name ->
            JsonObject(block(name) + ("type" to JsonPrimitive(type)))
        }
        messages += chatTurn("system", JsonArray(listOf(chatText(words)) + blocks))
    }

    /** At times a definition, at times a reference that offers again a tool the conversation already knows. */
    private fun added(name: String): JsonObject {
        val reoffer = name in known && random.nextInt(3) == 0
        if (reoffer) {
            current[name] = known.getValue(name)
            return buildJsonObject {
                putJsonObject("tool") {
                    put("type", "tool_reference")
                    put("name", name)
                }
            }
        }
        val props = setOf("v${version++}")
        known[name] = props
        current[name] = props
        return buildJsonObject {
            putJsonObject("tool") {
                put("type", "tool_definition")
                put("definition", declaration(name, props, deferred = false))
            }
        }
    }

    private fun removed(name: String): JsonObject {
        current.remove(name)
        return buildJsonObject {
            putJsonObject("tool") {
                put("type", "tool_reference")
                put("name", name)
            }
        }
    }

    private fun search() {
        val picked = (known.keys + "NoSuchTool").filter { random.nextInt(3) == 0 }
        picked.forEach { name -> known[name]?.let { current[name] = it } }
        val answer = picked.map { name ->
            buildJsonObject {
                put("type", "tool_reference")
                put("tool_name", name)
            }
        }.ifEmpty { listOf(chatText("No matching deferred tools found")) }
        call("ToolSearch", JsonArray(answer))
    }

    private fun call(name: String, result: JsonElement) {
        val id = "toolu_${calls++}"
        val use = buildJsonObject {
            put("type", "tool_use")
            put("id", id)
            put("name", name)
            putJsonObject("input") { put("query", "select:SendMessage") }
        }
        val answer = buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", id)
            put("content", result)
        }
        messages += chatTurn("assistant", JsonArray(listOf(use)))
        messages += chatTurn("user", JsonArray(listOf(answer)))
    }
}

private fun chatRequest(tools: List<JsonObject>, messages: List<JsonObject>): JsonObject = buildJsonObject {
    put("model", "claude-grok--grok-4.5")
    put("max_tokens", 1024)
    put("tools", JsonArray(tools))
    put("messages", JsonArray(messages))
}

private fun declaration(name: String, props: Set<String>, deferred: Boolean): JsonObject = buildJsonObject {
    put("name", name)
    put("description", "what $name does")
    putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") { props.forEach { putJsonObject(it) { put("type", "string") } } }
    }
    if (deferred) put("defer_loading", true)
}

private fun chatTurn(role: String, content: JsonElement): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun chatText(words: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", words)
}

private fun changeBlock(element: JsonElement): Boolean {
    val type = ((element as? JsonObject)?.get("type") as? JsonPrimitive)?.content
    return type == "tool_addition" || type == "tool_removal"
}

private fun withoutChangeBlocks(message: JsonObject): JsonObject {
    val content = message["content"] as? JsonArray ?: return message
    return JsonObject(message + ("content" to JsonArray(content.filterNot(::changeBlock))))
}

/** The tools a Chat request lets its model call, each with its schema's property names. */
private fun chatCallable(request: JsonObject): Map<String, Set<String>> =
    (request["tools"] as? JsonArray).orEmpty().map { it.jsonObject.getValue("function").jsonObject }.associate { fn ->
        fn.getValue("name").jsonPrimitive.content to
            (fn["parameters"]?.jsonObject?.get("properties") as? JsonObject)?.keys.orEmpty()
    }

/** Each Claude Code tool as this dialect has always declared it: a function with its name, words and schema. */
private fun declaredAsFunctions(request: JsonObject): JsonArray = JsonArray(
    request.getValue("tools").jsonArray.map { tool ->
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", tool.jsonObject.getValue("name"))
                put("description", tool.jsonObject.getValue("description"))
                put("parameters", tool.jsonObject.getValue("input_schema"))
            }
        }
    },
)

class ChatToolChangeTest {
    private val quirks = ChatQuirks(providerTag = "claude-grok")

    private fun built(request: JsonObject): JsonObject {
        val body = AnthropicParse.parseAnthropicBody(request.toString())
        val builder = ChatRequestBuilder(quirks)
        return builder.build(body.typed, upstreamModel = "grok-4.5", compact = false, raw = body.raw).req
    }

    @Test
    fun `a chat head offers exactly the conversation's current tools`() {
        val random = Random(1027)
        repeat(CHANGE_RUNS) {
            val case = ChatChangingConversation(random).case()
            val request = built(case.request)
            val why = Supplier<String?> { "built ${request["tools"]} for ${case.request}" }
            assertEquals(case.current, chatCallable(request), why)
            if (!case.changes) assertEquals(declaredAsFunctions(case.request), request["tools"], why)
        }
    }

    @Test
    fun `a tool change moves nothing but the tool list`() {
        val random = Random(2610)
        repeat(CHANGE_RUNS) {
            val case = ChatChangingConversation(random).case()
            val changed = built(case.request)
            val without = built(case.stripped)
            // A conversation that removed every tool sends no tools, and with them no tool_choice: a choice among no
            // tools is a request OpenAI-compatible validators refuse.
            val toolList = if ("tools" in changed) setOf("tools") else setOf("tools", "tool_choice")
            assertEquals(JsonObject(without - toolList), JsonObject(changed - toolList)) {
                "the change reached more than the tool list for ${case.request}"
            }
        }
    }
}
