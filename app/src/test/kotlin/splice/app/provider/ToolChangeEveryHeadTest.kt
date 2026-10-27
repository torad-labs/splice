// Goal: when Claude Code adds a tool mid-conversation (a `tool_addition` in a system message), removes one (a
// `tool_removal`), or loads a deferred one through ToolSearch (a `tool_reference` in its result), the model behind every
// head can call exactly the conversation's current tools, and no head is sent a shape its backend refuses. Anyone can
// tell by reading the request a head would send: the tools its model can call, with the schema the conversation last
// gave each, are the ones the conversation holds now, and nothing in it is an item or block that head refuses.
//
// Oct 27, 13:59 CT: Muse answered 400 "`input[8]` did not match any supported type" to every claude-muse turn once
// Claude Code added three Claude Docs tools in a system message, because splice sent each added tool as an
// `additional_tools` input item, which only the Codex backend takes. The same afternoon claude-kimi dropped the
// tool_addition and tool_removal blocks its upstream refuses, so Kimi's model never saw the added tools. These build
// each head the way the daemon builds it, from one topology, and read the request it would send.
package splice.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.parse.AnthropicParse
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.provider.muse.MuseKeyMintCall
import splice.provider.muse.MuseMintAttempt
import splice.topology.TopologyLoader
import splice.upstream.Provider
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private const val RUNS = 300
private const val MUSE = "claude-muse"
private const val CODEX = "claude-codex"
private const val KIMI = "claude-kimi"
private const val OPENAI = "claude-openai"
private const val XAI = "claude-xai"

/** Each head and the model its requests name. */
private val MODELS = mapOf(
    MUSE to "muse-wonder-full-1.0",
    CODEX to "gpt-5.5",
    KIMI to "kimi-k3",
    OPENAI to "gpt-5.5",
    XAI to "grok-4.5",
)

/** Heads whose backend refuses an `additional_tools` input item: Muse said so on Oct 27; the rest are not known to
 *  take it, and OpenRouter's Responses beta refuses it. */
private val NO_INLINE_CHANGES = setOf(MUSE, OPENAI, XAI)

/** Blocks Kimi's upstream refuses, which its head must never send. */
private val KIMI_REFUSES = setOf("tool_addition", "tool_removal", "tool_reference")

/** Claude Code's own tools. ToolSearch and Bash are always offered, since the conversation calls them. */
private val BUILT_IN =
    listOf("Bash", "Read", "ToolSearch", "SendMessage", "TaskStop", "Monitor", "DeferredToolPlaceholder")
private val ALWAYS = setOf("Bash", "ToolSearch")

/** MCP tools offered from the start: enough that Muse's tool surface defers them behind its tool search at times. */
private val CLIENT_MCP = listOf(
    "mcp__exa__web_search_exa", "mcp__exa__crawling_exa", "mcp__github__get_issue", "mcp__github__create_pull_request",
    "mcp__desk__read_process_output", "mcp__desk__run_process", "mcp__slack__post_message", "mcp__slack__read_channel",
    "mcp__linear__create_issue", "mcp__linear__list_issues",
)

/** MCP tools a server brings mid-conversation, as the Claude Docs server did on Oct 27. */
private val ADDED_MCP = listOf(
    "mcp__claude_ai_Claude_Docs__batch",
    "mcp__claude_ai_Claude_Docs__guide",
    "mcp__claude_ai_Claude_Docs__update",
    "mcp__figma__get_design",
)

/** One generated request, the tools its conversation holds at its end (each with its schema's property names), and the
 *  same request with every tool_addition and tool_removal block taken out. */
private data class ToolChangeCase(
    val request: JsonObject,
    val current: Map<String, Set<String>>,
    val stripped: JsonObject,
)

/** Claude Code's shape: a tool list, some of it deferred, and a conversation that adds, removes and loads tools between
 *  rounds of work. Every definition gets its own property name, so a stale schema shows as a different set. */
private class ToolChangeConversation(private val random: Random) {
    private var version = 0
    private var calls = 0
    private val known = linkedMapOf<String, Set<String>>()
    private val current = linkedMapOf<String, Set<String>>()
    private val messages = mutableListOf<JsonObject>()

    fun case(): ToolChangeCase {
        val tools = clientTools()
        messages += message("user", JsonPrimitive("finish the review"))
        repeat(random.nextInt(9)) { event() }
        messages += message("user", JsonPrimitive("carry on"))
        val request = request(tools, messages)
        return ToolChangeCase(request, current.toMap(), request(tools, messages.map(::withoutChanges)))
    }

    private fun clientTools(): List<JsonObject> {
        val names = BUILT_IN.filter { it in ALWAYS || random.nextInt(3) > 0 } +
            CLIENT_MCP.filter { random.nextInt(3) > 0 }
        return names.map { name ->
            val deferred = name !in ALWAYS && random.nextInt(3) == 0
            val props = setOf("v${version++}")
            known[name] = props
            if (!deferred) current[name] = props
            definition(name, props, deferred)
        }
    }

    private fun event() {
        when (random.nextInt(5)) {
            0 -> addition()
            1 -> removal()
            2 -> search()
            3 -> call("Bash", JsonPrimitive("done"))
            else -> {
                messages += message("assistant", JsonPrimitive("working on it"))
                messages += message("user", JsonPrimitive("go on"))
            }
        }
    }

    /** A server connects: its tools arrive as definitions, and at times one re-defines a tool the client had. */
    private fun addition() {
        val names = (ADDED_MCP + known.keys.take(2)).shuffled(random).take(random.nextInt(1, 4))
        val blocks = names.map { name ->
            val props = setOf("v${version++}")
            known[name] = props
            current[name] = props
            buildJsonObject {
                put("type", "tool_addition")
                putJsonObject("tool") {
                    put("type", "tool_definition")
                    put("definition", definition(name, props, deferred = false))
                }
            }
        }
        messages += message("system", JsonArray(listOf(text("These tools are now available.")) + blocks))
    }

    /** A server disconnects: references to tools it held, at times one no definition ever named. */
    private fun removal() {
        val names = (known.keys.toList() + "mcp__gone__tool").shuffled(random).take(random.nextInt(1, 4))
        val blocks = names.mapIndexed { at, name ->
            current.remove(name)
            buildJsonObject {
                put("type", "tool_removal")
                putJsonObject("tool") {
                    put("type", "tool_reference")
                    put("name", name)
                }
                if (at == names.lastIndex && random.nextBoolean()) {
                    putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            }
        }
        messages += message("system", JsonArray(listOf(text("These tools are no longer available.")) + blocks))
    }

    /** ToolSearch loads some known tools, at times names one no definition has, and at times finds nothing. */
    private fun search() {
        val picked = (known.keys.toList() + "NoSuchTool").filter { random.nextInt(4) == 0 }
        picked.forEach { name -> known[name]?.let { current[name] = it } }
        val answer = picked.map { name ->
            buildJsonObject {
                put("type", "tool_reference")
                put("tool_name", name)
            }
        }.ifEmpty { listOf(text("No matching deferred tools found")) }
        call("ToolSearch", JsonArray(answer))
    }

    private fun call(tool: String, result: JsonElement) {
        val id = "toolu_${calls++}"
        val use = buildJsonObject {
            put("type", "tool_use")
            put("id", id)
            put("name", tool)
            putJsonObject("input") { put("query", "select:SendMessage") }
        }
        val answer = buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", id)
            put("content", result)
        }
        messages += message("assistant", JsonArray(listOf(use)))
        messages += message("user", JsonArray(listOf(answer)))
    }
}

private fun request(tools: List<JsonObject>, messages: List<JsonObject>): JsonObject = buildJsonObject {
    put("max_tokens", 1024)
    put("stream", true)
    put("tools", JsonArray(tools))
    put("messages", JsonArray(messages))
}

/** A tool as Claude Code declares it: its name, words, schema, and `defer_loading` when ToolSearch must load it. */
private fun definition(name: String, props: Set<String>, deferred: Boolean): JsonObject = buildJsonObject {
    put("name", name)
    put("description", "what $name does")
    putJsonObject("input_schema") {
        put("type", "object")
        putJsonObject("properties") { props.forEach { putJsonObject(it) { put("type", "string") } } }
    }
    if (deferred) put("defer_loading", true)
}

private fun message(role: String, content: JsonElement): JsonObject = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun text(words: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", words)
}

private fun withoutChanges(message: JsonObject): JsonObject {
    val content = message["content"] as? JsonArray ?: return message
    val kept = content.filterNot { typeOf(it) == "tool_addition" || typeOf(it) == "tool_removal" }
    return JsonObject(message + ("content" to JsonArray(kept)))
}

private fun typeOf(element: JsonElement?): String? = ((element as? JsonObject)?.get("type") as? JsonPrimitive)?.content

private fun nameOf(tool: JsonObject): String = tool.getValue("name").jsonPrimitive.content

private fun propsOf(schema: JsonElement?): Set<String> =
    ((schema as? JsonObject)?.get("properties") as? JsonObject)?.keys.orEmpty()

/** The tools a Responses request lets its model call, each with its schema's property names: the function tools in its
 *  list (hosted-search deferred ones included, inside the lite namespace too), those a later item declares, the latest
 *  declaration winning, narrowed by an allowed_tools choice, and none when the choice is "none". */
private fun responsesCallable(body: JsonObject): Map<String, Set<String>> {
    val offered = linkedMapOf<String, Set<String>>()
    offerTools(body["tools"], offered)
    body.getValue("input").jsonArray.forEach { item ->
        if (typeOf(item) == "additional_tools" || typeOf(item) == "tool_search_output") {
            offerTools(item.jsonObject["tools"], offered)
        }
    }
    val choice = body["tool_choice"]
    if (choice == JsonPrimitive("none")) return emptyMap()
    val allowed = allowedNames(choice) ?: return offered
    return offered.filterKeys { it in allowed }
}

/** Each function tool in [tools], a namespace's included, into [offered], a later declaration replacing an earlier. */
private fun offerTools(tools: JsonElement?, offered: MutableMap<String, Set<String>>) {
    (tools as? JsonArray).orEmpty().map { it.jsonObject }.forEach { tool ->
        when (typeOf(tool)) {
            "function" -> offered[nameOf(tool)] = propsOf(tool["parameters"])
            "namespace" -> offerTools(tool["tools"], offered)
        }
    }
}

/** The names an allowed_tools choice narrows the list to, or null when the choice narrows nothing. */
private fun allowedNames(choice: JsonElement?): Set<String>? =
    (choice as? JsonObject)?.takeIf { typeOf(it) == "allowed_tools" }
        ?.getValue("tools")?.jsonArray?.map { nameOf(it.jsonObject) }?.toSet()

/** The tools an Anthropic-wire request lets its model call: every listed tool not held back by `defer_loading`. */
private fun anthropicCallable(body: JsonObject): Map<String, Set<String>> =
    (body["tools"] as? JsonArray).orEmpty().map { it.jsonObject }
        .filter { it["defer_loading"] != JsonPrimitive(true) }
        .associate { nameOf(it) to propsOf(it["input_schema"]) }

private fun callable(head: String, body: JsonObject): Map<String, Set<String>> =
    if (head == KIMI) anthropicCallable(body) else responsesCallable(body)

/** What in [body] its [head]'s backend refuses: an `additional_tools` item on a head that takes no inline change, and
 *  on Kimi's head a tool change or reference block anywhere in a message, a tool_result's content included. */
private fun refused(head: String, body: JsonObject): List<String> = when (head) {
    KIMI -> body.getValue("messages").jsonArray.flatMap { blockTypes(it.jsonObject["content"]) }
        .filter { it in KIMI_REFUSES }
    in NO_INLINE_CHANGES -> body.getValue("input").jsonArray.mapNotNull(::typeOf).filter { it == "additional_tools" }
    else -> emptyList()
}

private fun blockTypes(content: JsonElement?): List<String> = (content as? JsonArray).orEmpty().flatMap { block ->
    listOfNotNull(typeOf(block)) +
        if (typeOf(block) == "tool_result") blockTypes(block.jsonObject["content"]) else emptyList()
}

/** The five heads, built the way the daemon builds them from one topology: Muse over its minted-key sign-in, Codex
 *  over its ChatGPT sign-in with the standard conversion, Kimi on the Anthropic wire, and two API-key Responses heads. */
private class ToolChangeHeads(root: Path, scope: CoroutineScope) {
    private val providers: Map<String, Provider>

    init {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val museAuth = root.resolve("muse-auth.json")
        Files.writeString(museAuth, """{"access_token":"account-primary","api_key":"key-primary"}""")
        val codexAuth = root.resolve("codex-auth.json")
        Files.writeString(codexAuth, """{"tokens":{"access_token":"synthetic-primary","account_id":"primary-id"}}""")
        val topology = TopologyLoader.parse(topology(museAuth, codexAuth))
        val log = LogSink { }
        val noMint = MuseKeyMintCall { _, _ -> MuseMintAttempt.Denied("no mint in this test") }
        val assembly = ProviderAssembly(
            paths,
            scope,
            log = log,
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
            museArm = MuseResponsesArm(paths, log, scope, noMint),
        )
        val config = ConfigService(paths, envReader = { null })
        providers = MODELS.keys.associateWith { key ->
            val head = topology.heads.getValue(key)
            val provider = topology.providers.getValue(head.provider)
            assembly.buildProvider(
                ProviderBuild(
                    key = key,
                    head = head,
                    providerCfg = provider,
                    catalog = provider.catalogFor(head),
                    faultPlan = UpstreamFaultPlan(
                        watchdog = WatchdogBudget(30.seconds, 30.seconds, 60.seconds),
                        loginCommand = "$key login",
                    ),
                    cfg = config.getConfig(key),
                ),
            ).provider
        }
    }

    /** The request [head] would send upstream for Claude Code's [body]. */
    fun request(head: String, body: JsonObject): JsonObject {
        val named = JsonObject(body + ("model" to JsonPrimitive("$head--${MODELS.getValue(head)}")))
        return providers.getValue(head)
            .buildTurn(AnthropicParse.parseAnthropicBody(named.toString()), compact = false, sessionId = "tool-change")
            .requestBody
    }

    fun close() {
        providers.values.forEach(Provider::onHeadStop)
    }

    private fun topology(museAuth: Path, codexAuth: Path): String = providers(museAuth, codexAuth) + "\n\n" + heads()

    private fun providers(museAuth: Path, codexAuth: Path): String = """
        [providers.muse]
        dialect = "openai-responses"
        base_url = "https://api.meta.ai/v1"
        auth = { kind = "muse-oauth", file = "${museAuth.toString().replace("\\", "/")}" }

        [[providers.muse.models]]
        id = "${MODELS.getValue(MUSE)}"
        context_window = 1000000

        [providers.codex]
        dialect = "openai-responses"
        base_url = "https://chatgpt.com/backend-api/codex"
        auth = { kind = "chatgpt-oauth", file = "${codexAuth.toString().replace("\\", "/")}" }

        [providers.codex.quirks]
        code_mode = false

        [[providers.codex.models]]
        id = "${MODELS.getValue(CODEX)}"
        context_window = 272000

        [providers.kimi]
        dialect = "anthropic-passthrough"
        base_url = "https://api.kimi.com/coding"
        auth = { kind = "api-key", env = "SPLICE_TEST_KIMI_KEY" }

        [[providers.kimi.models]]
        id = "${MODELS.getValue(KIMI)}"
        context_window = 262144

        [providers.openai]
        dialect = "openai-responses"
        base_url = "https://api.openai.com/v1"
        auth = { kind = "api-key", env = "SPLICE_TEST_OPENAI_KEY" }

        [[providers.openai.models]]
        id = "${MODELS.getValue(OPENAI)}"
        context_window = 272000

        [providers.xai]
        dialect = "openai-responses"
        base_url = "https://api.x.ai/v1"
        auth = { kind = "api-key", env = "SPLICE_TEST_XAI_KEY" }

        [[providers.xai.models]]
        id = "${MODELS.getValue(XAI)}"
        context_window = 256000
    """.trimIndent()

    private fun heads(): String = """
        [heads.claude-muse]
        provider = "muse"
        port = 31501
        discovery_prefix = "claude-muse--"
        pinned_model = "${MODELS.getValue(MUSE)}"

        [heads.claude-codex]
        provider = "codex"
        port = 31502
        discovery_prefix = "claude-codex--"
        pinned_model = "${MODELS.getValue(CODEX)}"

        [heads.claude-kimi]
        provider = "kimi"
        port = 31503
        discovery_prefix = "claude-kimi--"
        pinned_model = "${MODELS.getValue(KIMI)}"

        [heads.claude-openai]
        provider = "openai"
        port = 31504
        discovery_prefix = "claude-openai--"
        pinned_model = "${MODELS.getValue(OPENAI)}"

        [heads.claude-xai]
        provider = "xai"
        port = 31505
        discovery_prefix = "claude-xai--"
        pinned_model = "${MODELS.getValue(XAI)}"
    """.trimIndent()
}

/** The Oct 27 conversation's shape at three points: the Claude Docs server connected in message 4, ToolSearch then
 *  loaded the deferred SendMessage, and the server disconnected. Each point is the whole request Claude Code sends. */
private object DocsServer {
    val ADDED = listOf(
        "mcp__claude_ai_Claude_Docs__batch",
        "mcp__claude_ai_Claude_Docs__guide",
        "mcp__claude_ai_Claude_Docs__update",
    )
    private val CLIENT =
        listOf("Bash", "Read", "ToolSearch", "Monitor", "SendMessage", "TaskStop", "DeferredToolPlaceholder")
    private val DEFERRED = setOf("SendMessage", "TaskStop", "DeferredToolPlaceholder")

    /** Each tool's property names: one per tool, the same at every point. */
    fun props(name: String): Set<String> = setOf("${name.substringAfterLast("__").lowercase()}_input")

    private val tools = CLIENT.map { definition(it, props(it), deferred = it in DEFERRED) }

    private val opening = listOf(
        message("user", JsonPrimitive("draft the release notes")),
        message("assistant", JsonPrimitive("Reading the changelog first.")),
        message("user", JsonPrimitive("use the docs server")),
        message(
            "system",
            JsonArray(
                listOf(text("The Claude Docs server connected.")) + ADDED.map { name ->
                    buildJsonObject {
                        put("type", "tool_addition")
                        putJsonObject("tool") {
                            put("type", "tool_definition")
                            put("definition", definition(name, props(name), deferred = false))
                        }
                    }
                },
            ),
        ),
    )

    private val search = listOf(
        message(
            "assistant",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", "toolu_search")
                        put("name", "ToolSearch")
                        putJsonObject("input") { put("query", "select:SendMessage") }
                    },
                ),
            ),
        ),
        message(
            "user",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "toolu_search")
                        put(
                            "content",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "tool_reference")
                                        put("tool_name", "SendMessage")
                                    },
                                ),
                            ),
                        )
                    },
                ),
            ),
        ),
    )

    private val disconnect = message(
        "system",
        JsonArray(
            listOf(text("The Claude Docs server disconnected.")) + ADDED.mapIndexed { at, name ->
                buildJsonObject {
                    put("type", "tool_removal")
                    putJsonObject("tool") {
                        put("type", "tool_reference")
                        put("name", name)
                    }
                    if (at == ADDED.lastIndex) putJsonObject("cache_control") { put("type", "ephemeral") }
                }
            },
        ),
    )

    private val next = message("user", JsonPrimitive("carry on"))

    val connected: JsonObject = request(tools, opening + next)
    val loaded: JsonObject = request(tools, opening + search + next)
    val disconnected: JsonObject = request(tools, opening + search + disconnect + next)

    /** What the model may call at each point. */
    private val eager = CLIENT.filterNot { it in DEFERRED }
    val callableConnected: Map<String, Set<String>> = (eager + ADDED).associateWith(::props)
    val callableLoaded: Map<String, Set<String>> = (eager + ADDED + "SendMessage").associateWith(::props)
    val callableDisconnected: Map<String, Set<String>> = (eager + "SendMessage").associateWith(::props)
}

class ToolChangeEveryHeadTest {

    @Test
    fun `every head offers exactly the conversation's current tools and nothing its backend refuses`(
        @TempDir tmp: Path,
    ) = runTest {
        val heads = ToolChangeHeads(tmp, backgroundScope)
        try {
            val random = Random(1027)
            repeat(RUNS) {
                val case = ToolChangeConversation(random).case()
                for (head in MODELS.keys) {
                    val request = heads.request(head, case.request)
                    val why = Supplier<String?> { "$head built ${request["tools"]} for ${case.request}" }
                    assertEquals(case.current, callable(head, request), why)
                    assertEquals(emptyList<String>(), refused(head, request), why)
                }
            }
        } finally {
            heads.close()
        }
    }

    @Test
    fun `on a head that takes no inline change, a tool change moves only the tool list`(@TempDir tmp: Path) = runTest {
        val heads = ToolChangeHeads(tmp, backgroundScope)
        try {
            val random = Random(2610)
            repeat(RUNS) {
                val case = ToolChangeConversation(random).case()
                for (head in NO_INLINE_CHANGES + KIMI) {
                    val changed = heads.request(head, case.request)
                    val without = heads.request(head, case.stripped)
                    assertEquals(JsonObject(without - "tools"), JsonObject(changed - "tools")) {
                        "$head: the change reached more than its tool list for ${case.request}"
                    }
                }
            }
        } finally {
            heads.close()
        }
    }

    @Test
    fun `muse offers the Claude Docs tools from its tool list, and drops them when the server disconnects`(
        @TempDir tmp: Path,
    ) = runTest {
        val heads = ToolChangeHeads(tmp, backgroundScope)
        try {
            val points = listOf(
                DocsServer.connected to DocsServer.callableConnected,
                DocsServer.loaded to DocsServer.callableLoaded,
                DocsServer.disconnected to DocsServer.callableDisconnected,
            )
            for ((sent, expected) in points) {
                val request = heads.request(MUSE, sent)
                val input = request.getValue("input").jsonArray.mapNotNull(::typeOf)
                assertTrue("additional_tools" !in input, "Muse refuses the additional_tools item: $input")
                val listed = request.getValue("tools").jsonArray.map { it.jsonObject }
                    .filter { typeOf(it) == "function" }
                    .associate { nameOf(it) to propsOf(it["parameters"]) }
                assertEquals(expected, listed, "Muse's tool list is what the conversation holds now")
            }
        } finally {
            heads.close()
        }
    }

    @Test
    fun `kimi's tool list carries the Claude Docs tools until the server disconnects`(@TempDir tmp: Path) = runTest {
        val heads = ToolChangeHeads(tmp, backgroundScope)
        try {
            val connected = heads.request(KIMI, DocsServer.connected)
            assertEquals(DocsServer.callableConnected, anthropicCallable(connected))
            assertEquals(emptyList<String>(), refused(KIMI, connected))
            assertEquals(DocsServer.callableLoaded, anthropicCallable(heads.request(KIMI, DocsServer.loaded)))
            val disconnected = heads.request(KIMI, DocsServer.disconnected)
            assertEquals(DocsServer.callableDisconnected, anthropicCallable(disconnected))
            val listed = disconnected.getValue("tools").jsonArray.map { nameOf(it.jsonObject) }
            assertTrue(DocsServer.ADDED.none { it in listed }, "a disconnected server's tools leave the list: $listed")
        } finally {
            heads.close()
        }
    }

    @Test
    fun `codex still takes each change at its place in the conversation`(@TempDir tmp: Path) = runTest {
        val heads = ToolChangeHeads(tmp, backgroundScope)
        try {
            val connected = heads.request(CODEX, DocsServer.connected)
            val items = connected.getValue("input").jsonArray.map { it.jsonObject }
            val declared = items.filter { typeOf(it) == "additional_tools" }
                .flatMap { item -> item.getValue("tools").jsonArray.map { nameOf(it.jsonObject) } }
            assertEquals(DocsServer.ADDED, declared, "each added tool is declared where the conversation added it")
            val listed = connected.getValue("tools").jsonArray.map { nameOf(it.jsonObject) }
            assertTrue(
                DocsServer.ADDED.none { it in listed },
                "the initial tool list stays as the client sent it: $listed",
            )
            val disconnected = heads.request(CODEX, DocsServer.disconnected)
            assertEquals(DocsServer.callableDisconnected, responsesCallable(disconnected))
        } finally {
            heads.close()
        }
    }
}
