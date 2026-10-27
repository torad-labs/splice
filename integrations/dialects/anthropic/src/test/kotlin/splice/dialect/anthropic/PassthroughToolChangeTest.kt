// Goal: on a passthrough head whose upstream refuses Claude Code's tool_addition and tool_removal blocks, the model can
// still call exactly the conversation's current tools: the tool list carries each change, since the blocks never
// reach the upstream. A tool the conversation added rides in the list with the schema it was last given, a tool it
// removed leaves the list until something offers it again, and a tool ToolSearch loaded rides without
// `defer_loading` where the upstream cannot read the reference that loads it. A head whose upstream reads the blocks
// gets the client's tools as sent, and on every head a tool change moves nothing but the tool list.
//
// Oct 27, 14:46 CT: claude-kimi logged "content block 'tool_addition' is absent from this head's block_allowlist;
// dropped from the request, so the upstream never sees it", and the same for 'tool_removal'. The three Claude Docs tools
// Claude Code added never reached Kimi's model. DeepSeek's endpoint reads tool_reference but refuses both change blocks
// (its own deserializer's list, 2026-09-16), so its head carries the changes the same way and leaves loading to it.
package splice.dialect.anthropic

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
import splice.upstream.ToolNameShortener
import java.util.function.Supplier
import kotlin.random.Random

private const val RUNS = 1_000
private const val CAP = 16
private const val DEFER = "defer_loading"

/** Claude Code's own tools, then MCP tools offered from the start, then MCP tools a server brings later. Several MCP
 *  names run past [CAP], so a capped head offers them shortened. */
private val CLIENT_TOOLS = listOf(
    "Bash",
    "ToolSearch",
    "Read",
    "SendMessage",
    "TaskStop",
    "DeferredToolPlaceholder",
    "mcp__exa__web_search_exa",
    "mcp__github__create_pull_request",
)
private val ADDED_TOOLS = listOf(
    "mcp__claude_ai_Claude_Docs__batch",
    "mcp__claude_ai_Claude_Docs__guide",
    "mcp__claude_ai_Claude_Docs__update",
)
private val ALWAYS = setOf("Bash", "ToolSearch")

/** One generated request, the tools its conversation holds at its end (each with its schema's property names), and the
 *  same request with every tool_addition and tool_removal block taken out. */
private data class ChangeCase(
    val request: JsonObject,
    val current: Map<String, Set<String>>,
    val stripped: JsonObject,
) {
    val tools: List<JsonObject> get() = request.getValue("tools").jsonArray.map { it.jsonObject }
}

/** A conversation that adds, removes and loads tools between rounds of work. Every definition gets its own property
 *  name, so a stale schema shows as a different set. */
private class ChangingConversation(private val random: Random) {
    private var version = 0
    private var calls = 0
    private val known = linkedMapOf<String, Set<String>>()
    private val current = linkedMapOf<String, Set<String>>()
    private val messages = mutableListOf<JsonObject>()

    fun case(): ChangeCase {
        val tools = CLIENT_TOOLS.filter { it in ALWAYS || random.nextInt(4) > 0 }.map { name ->
            val deferred = name !in ALWAYS && random.nextInt(2) == 0
            val props = setOf("v${version++}")
            known[name] = props
            if (!deferred) current[name] = props
            tool(name, props, deferred)
        }
        messages += turn("user", JsonPrimitive("finish the review"))
        repeat(random.nextInt(9)) {
            when (random.nextInt(4)) {
                0 -> change("tool_addition", "These tools are now available.", ::added)
                1 -> change("tool_removal", "These tools are no longer available.", ::removed)
                2 -> search()
                else -> call("Bash", JsonPrimitive("done"))
            }
        }
        messages += turn("user", JsonPrimitive("carry on"))
        return ChangeCase(
            changeRequest(tools, messages),
            current.toMap(),
            changeRequest(tools, messages.map(::unchanged)),
        )
    }

    private fun change(type: String, words: String, block: (String) -> JsonObject) {
        val pool = if (type == "tool_addition") ADDED_TOOLS + known.keys.take(2) else known.keys + "mcp__gone__tool"
        val blocks = pool.shuffled(random).take(random.nextInt(1, 4)).map { name ->
            JsonObject(block(name) + ("type" to JsonPrimitive(type)))
        }
        messages += turn("system", JsonArray(listOf(words(words)) + blocks))
    }

    private fun added(name: String): JsonObject {
        val props = setOf("v${version++}")
        known[name] = props
        current[name] = props
        return buildJsonObject {
            putJsonObject("tool") {
                put("type", "tool_definition")
                put("definition", tool(name, props, deferred = false))
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
        }.ifEmpty { listOf(words("No matching deferred tools found")) }
        call("ToolSearch", JsonArray(answer))
    }

    private fun call(name: String, result: JsonElement) {
        val id = "toolu_${calls++}"
        messages += turn(
            "assistant",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", id)
                        put("name", name)
                        putJsonObject("input") { put("query", "select:SendMessage") }
                    },
                ),
            ),
        )
        messages += turn(
            "user",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("content", result)
                    },
                ),
            ),
        )
    }
}

private fun changeRequest(tools: List<JsonObject>, messages: List<JsonObject>): JsonObject = buildJsonObject {
    put("model", "claude-kimi--k3")
    put("messages", JsonArray(messages))
    put("tools", JsonArray(tools))
}

private fun tool(name: String, props: Set<String>, deferred: Boolean): JsonObject = buildJsonObject {
    put("name", name)
    put("description", "what $name does")
    putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") { props.forEach { putJsonObject(it) { put("type", "string") } } }
    }
    if (deferred) put(DEFER, true)
}

private fun turn(role: String, content: JsonElement): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun words(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}

private fun unchanged(message: JsonObject): JsonObject {
    val content = message["content"] as? JsonArray ?: return message
    val kept = content.filterNot { block -> blockType(block) == "tool_addition" || blockType(block) == "tool_removal" }
    return JsonObject(message + ("content" to JsonArray(kept)))
}

private fun blockType(element: JsonElement?): String? =
    ((element as? JsonObject)?.get("type") as? JsonPrimitive)?.content

private fun nameOf(tool: JsonObject): String = tool.getValue("name").jsonPrimitive.content

private fun propsOf(tool: JsonObject): Set<String> =
    ((tool["input_schema"] as? JsonObject)?.get("properties") as? JsonObject)?.keys.orEmpty()

/** Every `tool_name` a reference in [blocks] holds, inside a tool_result's content too. */
private fun referenced(blocks: JsonElement?): Set<String> = (blocks as? JsonArray).orEmpty().flatMap { block ->
    val name = ((block as? JsonObject)?.get("tool_name") as? JsonPrimitive)?.content
    val own = listOfNotNull(name.takeIf { blockType(block) == "tool_reference" })
    own + if (blockType(block) == "tool_result") referenced(block.jsonObject["content"]) else emptySet()
}.toSet()

/** The request [quirks]' head builds from [request], and the name map its builder used. */
private fun built(quirks: PassthroughQuirks, request: JsonObject): Pair<JsonObject, ToolNameShortener> {
    val names = ToolNameShortener(quirks.request.toolNameCap)
    val req = PassthroughRequestBuilder(quirks, names = names).build(
        AnthropicParse.parseAnthropicBody(request.toString()),
        upstreamModel = "k3",
        originalModel = "claude-kimi--k3",
        compact = false,
    ).req
    return req to names
}

/** What the model behind [request] can call, by the client's names: a listed tool without `defer_loading`, and a
 *  deferred one a reference in the request loads where the upstream reads references itself. */
private fun callable(
    request: JsonObject,
    names: ToolNameShortener,
    original: Collection<String>,
): Map<String, Set<String>> {
    val loaded = request.getValue("messages").jsonArray.flatMap { referenced(it.jsonObject["content"]) }.toSet()
    val byWire = (original + ADDED_TOOLS).associateBy { names.shorten(it) }
    return (request["tools"] as? JsonArray).orEmpty().map { it.jsonObject }
        .filter { it[DEFER] != JsonPrimitive(true) || nameOf(it) in loaded }
        .associate { (byWire[nameOf(it)] ?: nameOf(it)) to propsOf(it) }
}

class PassthroughToolChangeTest {

    private val kimi = KimiProfileFixture().kimi("claude-kimi")

    /** Kimi's allowlist on a head that shortens tool names. */
    private val capped = PassthroughQuirks(
        providerTag = "claude-capped",
        request = PassthroughRequestQuirks(blockAllowlist = kimi.request.blockAllowlist, toolNameCap = CAP),
    )

    /** DeepSeek's endpoint as its deserializer listed it on 2026-09-16: it reads tool_reference, not the change blocks. */
    private val deepseek = PassthroughQuirks(
        providerTag = "claude-deepseek",
        request = PassthroughRequestQuirks(
            blockAllowlist = setOf(
                "text", "tool_reference", "image", "document", "server_tool_use", "tool_use", "tool_result",
                "web_search_tool_result", "thinking",
            ),
            stripCacheControl = true,
        ),
    )

    /** Heads whose upstream reads every one of these blocks: Anthropic's, with no allowlist, and one naming them all. */
    private val readsChanges = listOf(
        PassthroughQuirks(providerTag = "claude-splice"),
        PassthroughQuirks(
            providerTag = "claude-reader",
            request = PassthroughRequestQuirks(
                blockAllowlist = kimi.request.blockAllowlist.orEmpty() +
                    setOf("tool_reference", "tool_addition", "tool_removal"),
            ),
        ),
    )

    @Test
    fun `a head whose upstream refuses the change blocks offers exactly the conversation's current tools`() {
        val random = Random(1027)
        repeat(RUNS) {
            val case = ChangingConversation(random).case()
            for (quirks in listOf(kimi, capped, deepseek)) {
                val (request, names) = built(quirks, case.request)
                val why = Supplier<String?> { "${quirks.providerTag} built ${request["tools"]} for ${case.request}" }
                assertEquals(case.current, callable(request, names, case.tools.map(::nameOf)), why)
            }
        }
    }

    @Test
    fun `a head whose upstream reads the change blocks gets the client's tools as sent`() {
        val random = Random(2027)
        repeat(RUNS) {
            val case = ChangingConversation(random).case()
            for (quirks in readsChanges) {
                val (request, _) = built(quirks, case.request)
                assertEquals(case.tools, request.getValue("tools").jsonArray.map { it.jsonObject }) {
                    "${quirks.providerTag}: ${case.request}"
                }
            }
        }
    }

    @Test
    fun `a tool change moves nothing but the tool list`() {
        val random = Random(2610)
        repeat(RUNS) {
            val case = ChangingConversation(random).case()
            for (quirks in listOf(kimi, capped, deepseek) + readsChanges) {
                val (changed, _) = built(quirks, case.request)
                val (without, _) = built(quirks, case.stripped)
                val sent = if (quirks in readsChanges) changed - "messages" - "tools" else changed - "tools"
                val expected = if (quirks in readsChanges) without - "messages" - "tools" else without - "tools"
                assertEquals(JsonObject(expected), JsonObject(sent)) { "${quirks.providerTag}: ${case.request}" }
            }
        }
    }
}
