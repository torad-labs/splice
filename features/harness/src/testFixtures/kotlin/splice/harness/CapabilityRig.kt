// NEW: what the harness tests stand on (docs/specs/harness.md): a provider that records every request it is sent and
// answers on each dialect's own path (Anthropic's Messages, Chat Completions, Responses), a worker outside splice that
// answers in JSON lines the way it is told to, and real heads with the capabilities a test declares, on the provider a
// test hands in, which the test sends requests to the way Claude Code sends them.
package splice.harness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.capability.Capability
import splice.core.capability.CapabilityFailure
import splice.core.capability.CapabilityField
import splice.core.capability.CapabilityLimits
import splice.core.capability.CapabilityNotice
import splice.core.capability.CapabilityResult
import splice.core.capability.CapabilityRule
import splice.core.capability.CapabilityWork
import splice.core.capability.CapabilityWorker
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.MonoClock
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.compaction.SessionProjectLookup
import splice.head.headDeps
import splice.head.port.TurnHarness
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds

/** The head every rig serves, as splice.toml names it. */
public const val HEAD: String = "astra"

/** Claude Code 2.1.326's user agent in a terminal, and in a print-mode run. */
public const val INTERACTIVE_AGENT: String = "claude-cli/2.1.326 (external, cli)"
public const val PRINT_AGENT: String = "claude-cli/2.1.326 (external, sdk-cli)"

/** One request, sent the way Claude Code sends one: a turn streams and carries tools. */
public data class Turn(
    val session: String,
    val messages: String,
    val userAgent: String = INTERACTIVE_AGENT,
    val agent: String? = null,
    val tools: Boolean = true,
    val model: String = "claude-astra--astra-large",
    val system: String? = null,
)

/** How the worker answers: with [status] and, from a 200, [lines]; [held] answers only once the rig lets go. */
public data class WorkerReply(val lines: List<String>, val status: Int = 200, val held: Boolean = false)

/** What a worker was posted: the capability it was asked for, the session, and the fields it was sent. */
public data class Asked(val capability: String, val session: String, val fields: JsonObject = JsonObject(emptyMap()))

public class CapabilityRig(private val tmp: Path) : AutoCloseable {
    /** Each request the provider was sent, its body as it arrived. */
    public val provider: MutableList<String> = CopyOnWriteArrayList()

    /** Each post the worker got, in the order it got them. */
    public val asked: MutableList<Asked> = CopyOnWriteArrayList()

    /** How the worker answers a post, by the capability it is for. */
    @Volatile public var reply: (String) -> WorkerReply = { name -> result("$name found src/main.ts:10-42.") }

    /** What a held answer waits for: the rig closing. */
    private val letGo = CompletableFuture<Unit>()

    private val upstream = server("/") { ex ->
        provider += ex.requestBody.readAllBytes().decodeToString()
        val path = ex.requestURI.path
        val stream = when {
            path.endsWith("/chat/completions") -> CHAT_STREAM
            path.endsWith("/responses") -> RESPONSES_STREAM
            else -> MESSAGES_STREAM
        }
        answer(ex, HTTP_OK, stream, "text/event-stream")
    }

    private val worker = server("/work") { ex ->
        val body = Json.parseToJsonElement(ex.requestBody.readAllBytes().decodeToString()).jsonObject
        val capability = body.getValue("capability").jsonPrimitive.content
        asked += Asked(capability, body.getValue("session").jsonPrimitive.content, body.getValue("fields").jsonObject)
        val now = reply(capability)
        if (now.held) letGo.get(HELD_S, TimeUnit.SECONDS)
        answer(ex, now.status, now.lines.joinToString("") { "$it\n" }, "application/x-ndjson")
    }

    /** Each head the rig started, and the folder it writes in. */
    private val heads = LinkedHashMap<HeadServer, Path>()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }

    public val workerUrl: String get() = "http://127.0.0.1:${worker.address.port}/work"

    /** A URL nothing listens on. */
    public val nobody: String get() = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}/work" }

    /** A capability on this rig's head with splice.toml's defaults: a main thread's interactive first turn, its
     *  text placed in the first user message, sixty seconds, failing open. */
    public fun capability(name: String, url: String = workerUrl): Capability = Capability(
        name = name,
        heads = listOf(HEAD),
        rule = CapabilityRule(),
        result = CapabilityResult(),
        notice = CapabilityNotice("Reading the task", "cyan"),
        limits = CapabilityLimits(timeoutS = 60, onFailure = CapabilityFailure.OPEN),
        work = CapabilityWork(CapabilityWorker.Http(url, null), listOf(CapabilityField.FIRST_USER_MESSAGE)),
    )

    /** A started head declaring [capabilities] in this order, its threads recorded under [records], whose sessions all
     *  work in [folder] (none when null) and load the MCP servers [mcpServers] gives them, serving the provider
     *  [dialect] builds from the head's tuning: Anthropic's Messages passed through, unless a test names another. */
    public fun head(
        capabilities: List<Capability>,
        records: Path = tmp.resolve("records"),
        folder: Path? = null,
        mcpServers: WorkerMcpServers = WorkerMcpServers { _, _ -> emptyMap() },
        dialect: (ProviderTuning) -> Provider = { PassthroughProvider(it, PassthroughQuirks(providerTag = HEAD)) },
    ): HeadServer = runBlocking {
        val dir = Files.createDirectories(tmp.resolve("head-${heads.size}"))
        val harness = HarnessInputs(
            capabilities,
            records,
            WorkerKeys { null },
            mcpServers,
        ).takeIf { capabilities.isNotEmpty() }
            ?.let { HeadHarness().of(HEAD, it, LogSink { }, ElapsedClock(MonoClock::nowMs)) }
        val server = HeadServer(
            provider = dialect(tuning()),
            listenPort = 0,
            deps = headDeps(tmp = dir, seams = HeadDeps.HeadSeams(session = sessions(folder, harness))),
        )
        server.start()
        heads[server] = dir
        server
    }

    private fun sessions(folder: Path?, harness: TurnHarness?) =
        HeadDeps.SessionSeams(sessionProject = SessionProjectLookup { folder }, harness = harness)

    /** The perf rows [head] wrote, oldest first. */
    public fun perfRows(head: HeadServer): List<JsonObject> {
        val file = heads.getValue(head).resolve("perf.jsonl")
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
    }

    /** Sends [turn] to [head] and answers what the client's stream carried. */
    public suspend fun send(head: HeadServer, turn: Turn): String =
        client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("User-Agent", turn.userAgent)
            header("x-claude-code-session-id", turn.session)
            turn.agent?.let { header("x-claude-code-agent-id", it) }
            setBody(body(turn))
        }.bodyAsText()

    /** Polls [done] with a deadline, never a sleep for a duration (kt-tests-no-wall-clock). */
    public fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S)
        while (!done()) {
            check(System.nanoTime() < deadline) { "never happened within $AWAIT_S s: $what" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS))
        }
    }

    override fun close() {
        letGo.complete(Unit)
        runBlocking { heads.keys.forEach { it.stop() } }
        upstream.stop(0)
        worker.stop(0)
        client.close()
    }

    private fun body(turn: Turn): String {
        val tools = if (turn.tools) """"tools":[{"name":"Read","input_schema":{"type":"object"}}],""" else ""
        val system = turn.system?.let { """"system":${Json.encodeToString(it)},""" }.orEmpty()
        return """{"model":"${turn.model}","stream":true,"max_tokens":64,$tools$system"messages":${turn.messages}}"""
    }

    private fun tuning() = ProviderTuning(
        name = ProviderName(key = HEAD, label = "claude-astra"),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-astra--",
            models = listOf(
                ModelEntry("astra-large", "Astra Large", contextWindow = WINDOW),
                ModelEntry("astra-small", "Astra Small", contextWindow = WINDOW),
            ),
            defaultContextWindow = WINDOW,
        ),
        pinnedModel = "astra-large",
        auth = KeyAuth(),
        locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    )

    private fun server(path: String, handle: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext(path) { ex -> runCatching { handle(ex) }.also { ex.close() } }
            start()
        }

    private fun answer(ex: HttpExchange, status: Int, text: String, type: String) {
        val bytes = text.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private class KeyAuth : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.ApiKey("provider-key", "x-api-key", "")
        override suspend fun refresh(): Credentials = credentials()
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
    }
}

/** A worker's answers: a result carrying [text], a refusal with [reason]. */
public fun result(text: String): WorkerReply =
    WorkerReply(listOf("""{"type":"result","text":${Json.encodeToString(text)}}"""))

public fun refusal(reason: String): WorkerReply = WorkerReply(listOf("""{"type":"refusal","reason":"$reason"}"""))

/** A conversation's opening: what the person typed, as Claude Code sends it, with nothing answered yet. */
public fun opening(text: String): String = """[{"role":"user","content":[{"type":"text","text":"$text"}]}]"""

/** The same conversation one exchange later. */
public fun later(text: String, next: String): String =
    """[{"role":"user","content":[{"type":"text","text":"$text"}]},""" +
        """{"role":"assistant","content":[{"type":"text","text":"ok"}]},""" +
        """{"role":"user","content":[{"type":"text","text":"$next"}]}]"""

/** The conversation's opening user turn in [raw], a request on any wire: the run of user items, in `messages` or in
 *  `input`, from the first one to the first that is not one, as JSON. */
public fun openingUserTurn(raw: String): String {
    val body = Json.parseToJsonElement(raw).jsonObject
    val items = (body["messages"] ?: body["input"])?.jsonArray.orEmpty()
    val user = { item: JsonElement -> item.jsonObject["role"]?.jsonPrimitive?.content == "user" }
    return JsonArray(items.dropWhile { !user(it) }.takeWhile(user)).toString()
}

/** The texts of the first user message of [raw], a request on Anthropic's Messages wire. */
public fun firstUserTexts(raw: String): List<String> {
    val messages = Json.parseToJsonElement(raw).jsonObject.getValue("messages").jsonArray
    val first = messages.first { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }.jsonObject
    return first.getValue("content").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
}

private const val HTTP_OK = 200
private const val WINDOW = 200_000L
private const val HELD_S = 60L
private const val AWAIT_S = 20L
private const val POLL_MS = 5L

/** What each wire's provider answers every request with: a short text, then the end of the message. */
private const val MESSAGES_STREAM =
    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n" +
        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0," +
        "\"content_block\":{\"type\":\"text\"}}\n\n" +
        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0," +
        "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok from the provider\"}}\n\n" +
        "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
        "event: message_delta\ndata: {\"type\":\"message_delta\"," +
        "\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n" +
        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

private const val CHAT_STREAM =
    "data: {\"choices\":[{\"delta\":{\"content\":\"ok from the provider\"},\"finish_reason\":\"stop\"}]," +
        "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n"

private const val RESPONSES_STREAM =
    "data: {\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"message\"}}\n\n" +
        "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"ok from the provider\"}\n\n" +
        "data: {\"type\":\"response.output_item.done\",\"output_index\":0}\n\n" +
        "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_rig\",\"status\":\"completed\"," +
        "\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
