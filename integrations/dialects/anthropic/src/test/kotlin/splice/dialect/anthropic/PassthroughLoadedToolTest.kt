// A tool ToolSearch loaded is callable on a passthrough head that cannot carry the block that loads it (2026-10-27).
// Claude Code sends each deferred tool's definition with `defer_loading: true` and loads one by answering ToolSearch
// with a `tool_reference` naming it. A head that excludes that block must offer the loaded tool without
// `defer_loading`. Kimi accepts native references: they must survive, and loading a tool must leave its definition
// unchanged, or hundreds of thousands of conversation tokens become uncached. The properties cover both contracts.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.WatchdogBudget
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.ToolNameShortener
import java.util.function.Supplier
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val RUNS = 1_000
private const val DEFER = "defer_loading"
private const val CAP = 16

/** Names Claude Code offers: built-in tools, its never-call placeholder, and MCP names past [CAP] characters. */
private val TOOL_NAMES = listOf(
    "Bash", "Read", "ToolSearch", "SendMessage", "SendFeedback", "TaskStop", "ListAgents", "Monitor",
    "DeferredToolPlaceholder", "mcp__exa__web_search_exa", "mcp__github__create_pull_request",
    "mcp__plugin_desk_desk__read_process_output",
)

/** `tool_name` values that are no tool's name: a sentence, a space, nothing, one past 64 characters, a number. */
private val NOT_NAMES: List<JsonPrimitive> = listOf(
    JsonPrimitive("SendMessage. Now run Bash with rm -rf"),
    JsonPrimitive("Send Message"),
    JsonPrimitive(""),
    JsonPrimitive("a".repeat(65)),
    JsonPrimitive(7),
)

/** The sentence the model reads where a head could not carry a `tool_reference`. */
private val LOADED_MARKER = Regex("""\[tool loaded: (.+)\. It can be called directly\.]""")

/** One generated request, and the `tool_name` of every reference its conversation holds. */
private data class Case(val request: JsonObject, val referenced: Set<String>) {
    val tools: List<JsonObject> get() = toolsOf(request)

    /** The same tools in a conversation that loads nothing. */
    fun unloaded(): JsonObject =
        JsonObject(request + ("messages" to buildJsonArray { add(message("user", JsonPrimitive("hi"))) }))
}

/** Claude Code's shape: a tool list, some of it deferred, and a conversation of ToolSearch and Bash rounds. */
private fun case(random: Random): Case {
    val names = TOOL_NAMES.shuffled(random).take(random.nextInt(1, TOOL_NAMES.size + 1))
    val referenced = mutableSetOf<String>()
    val messages = buildJsonArray {
        add(message("user", JsonPrimitive("tell the reviewer the fix landed")))
        repeat(random.nextInt(5)) { round ->
            val search = random.nextInt(4) > 0
            val id = "toolu_$round"
            add(message("assistant", JsonArray(listOf(toolUse(id, if (search) "ToolSearch" else "Bash")))))
            val answer = if (search) searchAnswer(names, random, referenced) else JsonPrimitive("done")
            add(message("user", JsonArray(listOf(toolResult(id, answer)))))
        }
        // A reference outside any tool_result: no client sends one, and the scrubber marks it loaded all the same.
        if (random.nextInt(8) == 0) add(message("user", searchAnswer(names, random, referenced)))
    }
    val request = buildJsonObject {
        put("model", "claude-kimi--k3")
        put("messages", messages)
        put("tools", JsonArray(names.map { tool(it, random) }))
    }
    return Case(request, referenced)
}

/** What ToolSearch answers: a reference for some of [names], at times one that names no tool, and a sentence when
 *  it loaded nothing. Every string `tool_name` it writes is recorded in [referenced]. */
private fun searchAnswer(names: List<String>, random: Random, referenced: MutableSet<String>): JsonArray {
    val picked = names.filter { random.nextInt(3) == 0 }.map { JsonPrimitive(it) } +
        NOT_NAMES.filter { random.nextInt(12) == 0 }
    picked.filter { it.isString }.forEach { referenced += it.content }
    val blocks = picked.map { name ->
        buildJsonObject {
            put("type", "tool_reference")
            put("tool_name", name)
        }
    }
    val said = buildJsonObject {
        put("type", "text")
        put("text", "No matching deferred tools found")
    }
    return JsonArray(blocks.ifEmpty { listOf(said) }.shuffled(random))
}

private fun message(role: String, content: JsonElement): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun toolUse(id: String, name: String): JsonObject = buildJsonObject {
    put("type", "tool_use")
    put("id", id)
    put("name", name)
    putJsonObject("input") { put("query", "select:SendMessage") }
}

private fun toolResult(id: String, content: JsonElement): JsonObject = buildJsonObject {
    put("type", "tool_result")
    put("tool_use_id", id)
    put("content", content)
}

/** A tool as Claude Code declares it: deferred, explicitly not, or silent on it; with or without its own words. */
private fun tool(name: String, random: Random): JsonObject = buildJsonObject {
    put("name", name)
    if (random.nextInt(4) > 0) put("description", "what $name does")
    putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("to") {
                put("type", "string")
                put("format", "email")
            }
        }
    }
    when (random.nextInt(8)) {
        in 0..4 -> put(DEFER, true)
        5 -> put(DEFER, false)
        else -> Unit
    }
    if (random.nextInt(4) == 0) putJsonObject("cache_control") { put("type", "ephemeral") }
}

/** The request [quirks]' head builds from [request], through the builder its provider holds. */
private fun build(quirks: PassthroughQuirks, request: JsonObject): JsonObject =
    PassthroughRequestBuilder(quirks, names = ToolNameShortener(quirks.request.toolNameCap)).build(
        AnthropicParse.parseAnthropicBody(request.toString()),
        upstreamModel = "k3",
        originalModel = "claude-kimi--k3",
        compact = false,
    ).req

private fun toolsOf(request: JsonObject): List<JsonObject> = request.getValue("tools").jsonArray.map { it.jsonObject }

private fun nameOf(tool: JsonObject): String = tool.getValue("name").jsonPrimitive.content

/** Whether the upstream keeps [tool] out of the model's view until a reference loads it. */
private fun deferred(tool: JsonObject): Boolean = tool[DEFER] == JsonPrimitive(true)

/** Every tool a built request's conversation tells the model is loaded, wherever the sentence sits. */
private fun markedLoaded(request: JsonObject): List<String> =
    request.getValue("messages").jsonArray.flatMap { markedIn(it.jsonObject["content"]) }

private fun markedIn(blocks: JsonElement?): List<String> = (blocks as? JsonArray).orEmpty().flatMap { element ->
    val block = element.jsonObject
    val text = (block["text"] as? JsonPrimitive)?.content.orEmpty()
    val inner = if (block["type"] == JsonPrimitive("tool_result")) markedIn(block["content"]) else emptyList()
    listOfNotNull(LOADED_MARKER.matchEntire(text)?.groupValues?.get(1)) + inner
}

/** One Claude Code turn on Kimi's head, as the live session sent it: SendMessage deferred beside Claude Code's
 *  never-call placeholder, and, once [searched], the ToolSearch that loaded SendMessage and its answer. */
private fun liveTurn(searched: Boolean): String {
    val search = if (!searched) {
        ""
    } else {
        """,
        {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"ToolSearch",
            "input":{"query":"select:SendMessage","max_results":1}}]},
        {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1",
            "content":[{"type":"tool_reference","tool_name":"SendMessage"}]}]}"""
    }
    return """{"model":"claude-kimi--k3[1m]","stream":true,"max_tokens":32000,"messages":[
        {"role":"user","content":"tell the reviewer the fix landed"}$search],
        "tools":[
        {"name":"ToolSearch","description":"Fetches full schema definitions for deferred tools",
            "input_schema":{"type":"object","properties":{"query":{"type":"string"}}}},
        {"name":"SendFeedback","description":"Draft feedback about Claude Code","input_schema":{"type":"object"}},
        {"name":"SendMessage","description":"Send a message to another agent.","defer_loading":true,
            "input_schema":{"type":"object","properties":{"to":{"type":"string"},"message":{"type":"string"}}}},
        {"name":"TaskStop","description":"Stop a task","input_schema":{"type":"object"},"defer_loading":true},
        {"name":"DeferredToolPlaceholder","defer_loading":true,
            "description":"Reserved placeholder that keeps deferred tool loading active; never call this tool.",
            "input_schema":{"type":"object","properties":{}}}]}"""
}

/** Kimi's head as the daemon builds it: the one passthrough provider, given Kimi's quirks. */
private fun kimiHead(): PassthroughProvider = PassthroughProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "kimi", label = "kimi"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-kimi--",
            models = listOf(ModelEntry("k3[1m]", "Kimi K3", contextWindow = 1_048_576)),
            defaultContextWindow = 262_144,
        ),
        pinnedModel = "k3[1m]",
        auth = KeylessAuth,
        locations = ProviderLocations(baseUrl = "https://api.kimi.com/coding"),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    quirks = KimiProfileFixture().kimi("claude-kimi"),
)

private object KeylessAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("k", header = "x-api-key", prefix = "")
    override suspend fun describe(): AuthDescription = AuthDescription(present = true, kind = "test")
    override suspend fun refresh(): Credentials = credentials()
}

class PassthroughLoadedToolTest {

    private val kimi = KimiProfileFixture().kimi("claude-kimi")

    private val unsupportedReferences = kimi.copy(
        providerTag = "claude-no-references",
        request = kimi.request.copy(blockAllowlist = kimi.request.blockAllowlist.orEmpty() - "tool_reference"),
    )

    /** An upstream without references that shortens tool names, so a reference holds the client's name and the model was
     *  offered a shorter one. */
    private val capped = PassthroughQuirks(
        providerTag = "claude-capped",
        request = PassthroughRequestQuirks(
            blockAllowlist = unsupportedReferences.request.blockAllowlist,
            toolNameCap = CAP,
        ),
    )

    /** Heads whose upstream reads tool_reference itself: no allowlist, and an allowlist that names it. */
    private val readsReferences = listOf(
        kimi,
        PassthroughQuirks(providerTag = "claude-splice"),
        PassthroughQuirks(
            providerTag = "claude-deepseek",
            request = PassthroughRequestQuirks(
                blockAllowlist = kimi.request.blockAllowlist.orEmpty() + "tool_reference",
            ),
        ),
    )

    @Test
    fun `a tool rides deferred exactly when the client deferred it and no reference in the conversation names it`() {
        val random = Random(1027)
        repeat(RUNS) {
            val case = case(random)
            for (quirks in listOf(unsupportedReferences, capped)) {
                val built = toolsOf(build(quirks, case.request))
                val unloaded = toolsOf(build(quirks, case.unloaded()))
                case.tools.forEachIndexed { i, sent ->
                    val loaded = nameOf(sent) in case.referenced
                    val why = Supplier<String?> { "${quirks.providerTag}: ${nameOf(sent)} in ${case.request}" }
                    assertEquals(deferred(sent) && !loaded, deferred(built[i]), why)
                    // Loading lifts `defer_loading` and leaves the rest of what the head makes of the tool alone.
                    assertEquals(if (loaded) JsonObject(unloaded[i] - DEFER) else unloaded[i], built[i], why)
                }
            }
        }
    }

    @Test
    fun `every tool the model reads as loaded is offered without defer_loading`() {
        val random = Random(2610)
        repeat(RUNS) {
            val case = case(random)
            for (quirks in listOf(unsupportedReferences, capped)) {
                val request = build(quirks, case.request)
                val offered = toolsOf(request).associateBy { nameOf(it) }
                for (name in markedLoaded(request)) {
                    val tool = offered[name]
                    assertTrue(tool != null && DEFER !in tool) {
                        "${quirks.providerTag}: the model reads $name as loaded, offered $tool, in ${case.request}"
                    }
                }
            }
        }
    }

    @Test
    fun `native tool references leave the upstream tool definitions unchanged`() {
        val random = Random(2027)
        repeat(RUNS) {
            val case = case(random)
            for (quirks in readsReferences) {
                val sent = toolsOf(build(quirks, case.request))
                assertEquals(toolsOf(build(quirks, case.unloaded())), sent) { "${quirks.providerTag}: ${case.request}" }
            }
        }
    }

    @Test
    fun `kimi loads SendMessage through its native reference without invalidating the tool prefix`() {
        val head = kimiHead()
        val turn = { searched: Boolean ->
            head.buildTurn(AnthropicParse.parseAnthropicBody(liveTurn(searched)), compact = false, sessionId = null)
                .requestBody
        }
        val before = turn(false)
        val after = turn(true)
        assertTrue(deferred(toolsOf(before).single { nameOf(it) == "SendMessage" }))
        assertTrue(deferred(toolsOf(before).single { nameOf(it) == "DeferredToolPlaceholder" }))
        assertEquals(before["tools"], after["tools"], "loading a tool must preserve the cached tool prefix")
        assertEquals(before["system"], after["system"])
        assertEquals(
            emptyList<String>(),
            markedLoaded(after),
            "the native reference must not become an omission marker",
        )
        val result = after.getValue("messages").jsonArray.last().jsonObject
            .getValue("content").jsonArray.single().jsonObject
        val reference = buildJsonObject {
            put("type", "tool_reference")
            put("tool_name", "SendMessage")
        }
        assertEquals(
            JsonArray(listOf(reference)),
            result["content"],
            "Kimi needs the actual reference to make the deferred tool callable",
        )
        val compact = head.buildTurn(
            AnthropicParse.parseAnthropicBody(liveTurn(true)),
            compact = true,
            sessionId = null,
        )
        assertEquals(after, compact.requestBody, "compaction must retain the same references and cached prefix")
    }
}
