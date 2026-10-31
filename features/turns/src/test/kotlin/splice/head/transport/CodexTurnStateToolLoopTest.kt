// Goal (fix 4 of the Oct 27 Responses fixes, artifacts/responses-gaps-2026-10-27/goals.md): the turn-state token the
// ChatGPT Codex backend returns is echoed on every later request of the same Claude Code turn, from the person's prompt
// through each tool-result request to the final answer, as Codex does across its tool loop, and a new prompt starts
// without one. Codex keeps one token per turn, first value kept (codex-rs core/src/client.rs: ModelClientSession's
// turn_state OnceLock), and sends it the way each transport carries it: the x-codex-turn-state request header over
// HTTP (build_responses_headers), and client_metadata["x-codex-turn-state"] in each websocket response.create (lines
// 2211-2212). The backend hands it over as a response header on HTTP and in a response.metadata event on the websocket
// (codex-api/src/endpoint/responses_websocket.rs:848-851). The token never appears in the trace, the daemon log or the
// console.
//
// These drive a real codex head the way Claude Code does: the person's prompt, then one request per tool result, each
// carrying the whole conversation, with a subagent's turn of the same session in between, against a scripted backend
// that answers each request with a turn-state as the ChatGPT backend does.
package splice.head.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.client.AGENT_HEADER
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.headDeps
import splice.head.headStores
import splice.head.scripted.ProviderRequest
import splice.head.scripted.ScriptedProvider
import splice.head.scripted.ScriptedReply
import splice.head.scripted.Transport
import splice.head.scripted.VirtualTime
import splice.head.scripted.reply
import splice.head.syntheticHeapBudget
import splice.head.syntheticTraceStore
import splice.head.trace.TraceRoute
import splice.head.trace.TraceSearchQuery
import splice.head.trace.body.TraceBodies
import splice.provider.codex.CodexProvider
import splice.provider.codex.CodexQuirks
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val HEAD = "codex"
private const val MODEL = "gpt-5.6-luna"
private const val SESSION = "synthetic-tool-loop-session"
private const val TURN_STATE = "x-codex-turn-state"

// What the backend hands back: the first prompt's token, a later value the same turn's backend sends on a tool-result
// request (Codex keeps the first one for the whole turn), a subagent's token, and the second prompt's token.
private const val FIRST_STATE = "synthetic-turn-state-first-prompt"
private const val LATER_STATE = "synthetic-turn-state-later-in-turn"
private const val SIDE_STATE = "synthetic-turn-state-subagent"
private const val SECOND_STATE = "synthetic-turn-state-second-prompt"
private val ALL_STATES = listOf(FIRST_STATE, LATER_STATE, SIDE_STATE, SECOND_STATE)

/** One request Claude Code sends: the conversation it carries, and the token the backend answers it with. */
private data class Step(val messages: List<String>, val backendState: String)

private val PROMPT_ONE = user("\"Run the synthetic probe on both files.\"")
private val CALL_ONE = toolUse("toolu_probe_1", "/tmp/one")
private val RESULT_ONE = toolResult("toolu_probe_1", "first synthetic result")
private val CALL_TWO = toolUse("toolu_probe_2", "/tmp/two")
private val RESULT_TWO = toolResult("toolu_probe_2", "second synthetic result")
private const val ANSWER_ONE = """{"role":"assistant","content":[{"type":"text","text":"Both probes ran."}]}"""
private val PROMPT_TWO = user("\"Now run it on the third file.\"")
private val CALL_THREE = toolUse("toolu_probe_3", "/tmp/three")
private val RESULT_THREE = toolResult("toolu_probe_3", "third synthetic result")

/** A subagent of the same session: its own conversation, its own first message. */
private val SUBAGENT_PROMPT = user("\"Summarize the synthetic side task.\"")

/** The person's first prompt, two tool results, a subagent's turn in between, then a second prompt and its tool
 *  result: what Claude Code sends through one session. */
private val LOOP = listOf(
    Step(listOf(PROMPT_ONE), FIRST_STATE),
    Step(listOf(PROMPT_ONE, CALL_ONE, RESULT_ONE), LATER_STATE),
    Step(listOf(SUBAGENT_PROMPT), SIDE_STATE),
    Step(listOf(PROMPT_ONE, CALL_ONE, RESULT_ONE, CALL_TWO, RESULT_TWO), LATER_STATE),
    Step(listOf(PROMPT_ONE, CALL_ONE, RESULT_ONE, CALL_TWO, RESULT_TWO, ANSWER_ONE, PROMPT_TWO), SECOND_STATE),
    Step(
        listOf(
            PROMPT_ONE, CALL_ONE, RESULT_ONE, CALL_TWO, RESULT_TWO, ANSWER_ONE, PROMPT_TWO, CALL_THREE,
            RESULT_THREE,
        ),
        SECOND_STATE,
    ),
)

/** What each request of [LOOP] carries: nothing on a prompt, the first token of its turn on every tool result, and
 *  nothing on the subagent's own prompt. */
private val ECHOED = listOf(null, FIRST_STATE, null, FIRST_STATE, null, SECOND_STATE)

/** A token the backend quotes inside an error's message, the way an error quotes the header map it refused: the member
 *  sits inside the message's escaped JSON string, one level down. */
private const val QUOTED_STATE = "synthetic-turn-state-quoted-in-an-error"

/** What a failing assertion prints in the token's place, so a leak is named without repeating it. */
private const val MASKED = "<the turn state>"
private const val QUOTING_MESSAGE = """the request's headers {"$TURN_STATE":"$QUOTED_STATE"} name no live turn"""

/** The answer a request of [LOOP] gets: [state] as a response header and in a response.metadata event, then text. */
private fun stateReply(state: String, n: Int) = reply {
    header(TURN_STATE, state)
    event("""{"type":"response.metadata","headers":{"$TURN_STATE":"$state"}}""")
    responsesAnswer("synthetic answer $n")
}

private fun responseReply(responseId: String, n: Int) = reply {
    event("""{"type":"response.created","response":{"id":"$responseId","status":"in_progress","output":[]}}""")
    event("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}""")
    event("""{"type":"response.output_text.delta","output_index":0,"delta":"synthetic answer $n"}""")
    event("""{"type":"response.output_item.done","output_index":0}""")
    event(
        """{"type":"response.completed","response":{"id":"$responseId","status":"completed","output":[],""" +
            """"usage":{"input_tokens":12,"output_tokens":5}}}""",
    )
}

private fun callReply(responseId: String) = reply {
    val call = """{"type":"function_call","call_id":"toolu_probe_1","name":"Read",""" +
        """"arguments":"{\"file_path\":\"/tmp/one\"}"}"""
    event("""{"type":"response.created","response":{"id":"$responseId","status":"in_progress","output":[]}}""")
    event("""{"type":"response.output_item.added","output_index":0,"item":$call}""")
    event("""{"type":"response.output_item.done","output_index":0,"item":$call}""")
    event(
        """{"type":"response.completed","response":{"id":"$responseId","status":"completed","output":[],""" +
            """"usage":{"input_tokens":12,"output_tokens":5}}}""",
    )
}

/** One way a backend refuses the person's prompt in words that quote the request's turn state: [replies] in the order
 *  the head meets them, on the websocket when [webSocket]. Each reaches the head's lines by its own path. */
private data class Refusal(val name: String, val webSocket: Boolean, val replies: List<ScriptedReply>)

private val REFUSALS = listOf(
    // A 400 and OpenAI's error body: the upstream-failure line, the turn's own line and the HTTP attempt record.
    Refusal(
        "an HTTP 400",
        webSocket = false,
        listOf(
            reply {
                status(400)
                openAiErrorBody("invalid_request_error", QUOTING_MESSAGE, "invalid_value")
            },
        ),
    ),
    // A 200 whose body is that error and no event, as a dead head answers: the zero-event line.
    Refusal(
        "an HTTP 200 with no event",
        webSocket = false,
        listOf(reply { openAiErrorBody("invalid_request_error", QUOTING_MESSAGE, "invalid_value") }),
    ),
    // The websocket's error event, as codex-rs reads one (codex-api/src/endpoint/responses_websocket.rs:600-617,
    // WrappedWebsocketErrorEvent): the websocket attempt record and the turn line.
    Refusal(
        "a websocket error event",
        webSocket = true,
        listOf(
            reply {
                event(
                    """{"type":"error","status":400,"error":{"type":"invalid_request_error","code":"invalid_value",""" +
                        """"message":${JsonPrimitive(QUOTING_MESSAGE)}}}""",
                )
            },
        ),
    ),
    // A websocket round the backend fails before any output, which the head serves again over HTTP: the fallback's
    // warning names the failure.
    Refusal(
        "a websocket round failed before any output",
        webSocket = true,
        listOf(
            reply {
                event(
                    """{"type":"response.failed","response":{"id":"resp_refused","status":"failed",""" +
                        """"error":{"code":"server_error","message":${JsonPrimitive(QUOTING_MESSAGE)}}}}""",
                )
            },
            reply { responsesAnswer("synthetic answer over HTTP") },
        ),
    ),
)

private fun user(content: String) = """{"role":"user","content":$content}"""

private fun toolUse(id: String, path: String) =
    """{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"Read",""" +
        """"input":{"file_path":"$path"}}]}"""

private fun toolResult(id: String, text: String) =
    """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"$text"}]}"""

class CodexTurnStateToolLoopTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `over HTTP every request of a Claude Code turn echoes its turn state, and a new prompt starts without one`() =
        runBlocking {
            val run = toolLoop(webSocket = false, traced = false)

            assertEquals(listOf(Transport.HTTP), run.requests.map { it.transport }.distinct())
            assertEquals(ECHOED, run.requests.map { it.headers[TURN_STATE] })
        }

    @Test
    fun `over the websocket every frame of a Claude Code turn carries its turn state, and a new prompt starts bare`() =
        runBlocking {
            val run = toolLoop(webSocket = true, traced = false)

            assertEquals(listOf(Transport.WEBSOCKET), run.requests.map { it.transport }.distinct())
            assertEquals(ECHOED, run.requests.map { frameState(it) })
            assertTrue(run.requests.none { TURN_STATE in it.headers }, "the websocket handshake carries none")
        }

    @Test
    fun `the turn state appears in no trace record, log line or console answer`() = runBlocking {
        for (webSocket in listOf(false, true)) {
            val run = toolLoop(webSocket, traced = true)

            val records = run.records()
            assertEquals(LOOP.size, records.count { it["kind"]?.jsonPrimitive?.content == "turn" }, "every turn traced")
            val turns = records.mapNotNull { it["turn"]?.jsonPrimitive?.content }.distinct()
            val console = run.route()
            val answers = turns.map { turn -> console.read(HEAD, turn).body } +
                ALL_STATES.map { state -> console.search(HEAD, TraceSearchQuery(state, null, null)).body }
            for (state in ALL_STATES) {
                assertFalse(records.any { state in it.toString() }, "a trace record holds a turn state (ws=$webSocket)")
                assertFalse(answers.any { state in it }, "a console answer holds a turn state (ws=$webSocket)")
                assertFalse(run.logs.any { state in it }, "a log line holds a turn state (ws=$webSocket)")
            }
            // The tokens were on the wire both ways, so every place above had the chance to keep one.
            assertEquals(ECHOED, run.requests.map { if (webSocket) frameState(it) else it.headers[TURN_STATE] })
        }
    }

    @Test
    fun `two subagents of one session whose first messages are identical each keep their own turn state`() =
        runBlocking {
            val rig = start("subagents", webSocket = false, traced = false)
            try {
                // Byte-identical requests, each headed with its own subagent: the same session, the same words.
                rig.backend.script(stateReply(FIRST_STATE, 1))
                rig.post(listOf(SUBAGENT_PROMPT), agent = "synthetic-agent-one")
                rig.backend.script(stateReply(SIDE_STATE, 2))
                rig.post(listOf(SUBAGENT_PROMPT), agent = "synthetic-agent-two")
                rig.backend.script(stateReply(SIDE_STATE, 3))
                rig.post(listOf(SUBAGENT_PROMPT, CALL_ONE, RESULT_ONE), agent = "synthetic-agent-two")
                rig.backend.awaitRequests(3)

                val echoed = rig.run().requests.map { it.headers[TURN_STATE] }
                assertEquals(listOf(null, null, SIDE_STATE), echoed, "each subagent's own turn, and no leak")
            } finally {
                rig.stop()
            }
        }

    @Test
    fun `same-opening websocket subagents continue their own response chains`() = runBlocking {
        val rig = start("subagent-ws-chains", webSocket = true, traced = false, replay = true)
        try {
            rig.backend.script(callReply("resp_agent_one_open"))
            val first = rig.post(listOf(SUBAGENT_PROMPT), agent = "synthetic-agent-one")
            assertTrue("toolu_probe_1" in first) { "$first\n${rig.run().logs.joinToString("\n")}" }
            rig.backend.script(callReply("resp_agent_two_open"))
            val second = rig.post(listOf(SUBAGENT_PROMPT), agent = "synthetic-agent-two")
            assertTrue("toolu_probe_1" in second) { "$second\n${rig.run().logs.joinToString("\n")}" }
            rig.backend.script(responseReply("resp_agent_one_next", 3))
            val third = rig.post(listOf(SUBAGENT_PROMPT, CALL_ONE, RESULT_ONE), agent = "synthetic-agent-one")
            assertTrue("synthetic answer 3" in third, third)
            rig.backend.script(responseReply("resp_agent_two_next", 4))
            val fourth = rig.post(listOf(SUBAGENT_PROMPT, CALL_ONE, RESULT_ONE), agent = "synthetic-agent-two")
            assertTrue("synthetic answer 4" in fourth, fourth)
            val requests = rig.backend.awaitRequests(4)
            assertEquals(4, requests.size, rig.run().logs.joinToString("\n"))

            val previous = requests.map {
                Json.parseToJsonElement(it.body).jsonObject["previous_response_id"]?.jsonPrimitive?.content
            }
            assertEquals(
                listOf(null, null, "resp_agent_one_open", "resp_agent_two_open"),
                previous,
                "neither subagent may inherit the other's server-side context",
            )
            assertEquals(listOf(Transport.WEBSOCKET), requests.map { it.transport }.distinct())
            assertEquals(1, requests.map { it.headers["session-id"] }.distinct().size)
            assertEquals(
                1,
                requests.map { Json.parseToJsonElement(it.body).jsonObject["prompt_cache_key"] }.distinct().size,
                "isolating continuity must not split the upstream prompt cache",
            )
        } finally {
            rig.stop()
        }
    }

    @Test
    fun `a turn state an error quotes inside its message appears in no trace record, log line or console answer`() =
        runBlocking {
            val leaks = REFUSALS.flatMap { refusal ->
                val run = refusedPrompt(refusal)
                assertTrue(run.requests.isNotEmpty(), "the backend answered with ${refusal.name}")
                leaks(run).map { "${refusal.name}: $it" }
            }
            // Each place it leaked, named with the token itself masked: a failure that printed it would print it twice.
            assertEquals(emptyList<String>(), leaks)
        }

    /** Every place [run] kept [QUOTED_STATE]: a trace record, by its kind; a console answer; a perf row, whose
     *  provider_message the console's Requests page shows; and a log line. */
    private suspend fun leaks(run: Run): List<String> {
        val records = run.records()
        val turns = records.mapNotNull { it["turn"]?.jsonPrimitive?.content }.distinct()
        assertTrue(turns.isNotEmpty(), "the refused turn was traced")
        val console = run.route()
        val answers = turns.map { turn -> console.read(HEAD, turn).body } +
            console.search(HEAD, TraceSearchQuery(QUOTED_STATE, null, null)).body
        val kept = records.filter { QUOTED_STATE in it.toString() }
            .map { "a ${it["kind"]?.jsonPrimitive?.content} trace record" } +
            answers.filter { QUOTED_STATE in it }.map { "a console answer" } +
            run.perfRows().filter { QUOTED_STATE in it }.map { "the perf row $it" } +
            run.logs.filter { QUOTED_STATE in it }.map { "the log line $it" }
        return kept.map { it.replace(QUOTED_STATE, MASKED) }
    }

    private fun frameState(request: ProviderRequest): String? =
        (Json.parseToJsonElement(request.body).jsonObject["client_metadata"] as? JsonObject)
            ?.get(TURN_STATE)?.jsonPrimitive?.content

    /** What a run left behind: what the backend received, the head's log, and what the head wrote under [dir]. */
    private class Run(val requests: List<ProviderRequest>, val logs: List<String>, private val dir: Path) {
        private val json = Json { ignoreUnknownKeys = true }
        private val traceDir: Path = dir.resolve("trace")

        /** Every record the head's trace wrote, bodies read back from where the store keeps them. */
        fun records(): List<JsonObject> {
            assertTrue(AsyncFileIo.drain(), "the trace's file lane drained")
            val files = Files.list(traceDir).use { listing -> listing.toList() }
                .filter { it.toString().endsWith(".jsonl") }
            val bodies = TraceBodies()
            return files.flatMap { file ->
                Files.readAllLines(file).map { bodies.hydrate(json.parseToJsonElement(it).jsonObject, file) }
            }
        }

        /** Every perf row the head wrote, which the console's Requests page reads: a failed turn's row carries the
         *  provider's own words. */
        fun perfRows(): List<String> {
            assertTrue(AsyncFileIo.drain(), "the perf file lane drained")
            val file = dir.resolve("perf.jsonl")
            return if (Files.exists(file)) Files.readAllLines(file).filter { it.isNotBlank() } else emptyList()
        }

        /** The console's trace routes over the same directory. */
        fun route(): TraceRoute {
            val none = object : HeadCompactSource {
                override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
            }
            val heads = TurnsHeadLookup { name -> if (name == HEAD) listOf(TurnsHead(HEAD, none)) else emptyList() }
            return TraceRoute(heads, { traceDir }, Dispatchers.Unconfined, heap = syntheticHeapBudget())
        }
    }

    private suspend fun toolLoop(webSocket: Boolean, traced: Boolean): Run {
        val rig = start(if (webSocket) "ws" else "http", webSocket, traced)
        try {
            LOOP.forEachIndexed { n, step ->
                rig.backend.script(stateReply(step.backendState, n + 1))
                val answer = rig.post(step.messages)
                assertTrue(answer.contains("synthetic answer ${n + 1}"), "request ${n + 1} was answered")
            }
            rig.backend.awaitRequests(LOOP.size)
            return rig.run()
        } finally {
            rig.stop()
        }
    }

    /** One prompt the backend meets with [refusal]'s replies, in order. */
    private suspend fun refusedPrompt(refusal: Refusal): Run {
        val rig = start("refused-${REFUSALS.indexOf(refusal)}", refusal.webSocket, traced = true)
        try {
            rig.backend.script(*refusal.replies.toTypedArray())
            rig.post(listOf(PROMPT_ONE))
            return rig.run()
        } finally {
            rig.stop()
        }
    }

    /** A codex head on a scripted backend, started, with its log, its perf rows and (when [traced]) its trace under
     *  [name]. */
    private suspend fun start(name: String, webSocket: Boolean, traced: Boolean, replay: Boolean = false): Rig {
        val dir = Files.createDirectories(tmp.resolve(name))
        val traceDir = dir.resolve("trace")
        val logs = CopyOnWriteArrayList<String>()
        val backend = ScriptedProvider(VirtualTime())
        backend.start()
        val trace = if (traced) {
            syntheticTraceStore(ActivityDays(traceDir, HEAD, 7, ownerOnly = true), HEAD, 1 shl 20)
        } else {
            null
        }
        val head = HeadServer(
            provider = provider(backend.baseUrl, webSocket, LogSink { logs += it }, replay),
            listenPort = 0,
            deps = headDeps(dir, upstream = UpstreamClient(totalTimeoutMs = 30_000), log = { logs += it })
                .copy(stores = headStores(dir, trace = trace)),
        )
        head.start()
        return Rig(backend, head, logs, dir)
    }

    /** A started head, its backend, and a client that posts to it as Claude Code does in [SESSION]. */
    private class Rig(
        val backend: ScriptedProvider,
        private val head: HeadServer,
        private val logs: List<String>,
        private val dir: Path,
    ) {
        private val client = HttpClient(CIO)

        /** One request carrying [messages], from the subagent [agent] names when one does; what the client read. */
        suspend fun post(messages: List<String>, agent: String? = null): String =
            client.post("http://127.0.0.1:${head.port}/v1/messages") {
                bearerAuth("test-inference-token")
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", SESSION)
                agent?.let { header(AGENT_HEADER, it) }
                setBody(
                    """{"model":"claude-codex--$MODEL","stream":true,"max_tokens":1024,""" +
                        """"tools":[{"name":"Read","input_schema":{"type":"object"}}],""" +
                        """"messages":[${messages.joinToString(",")}]}""",
                )
            }.bodyAsText()

        /** What the run left: every request the backend received so far, the log as it grows (a turn's last lines are
         *  written as it ends), and the head's folder. */
        fun run(): Run = Run(backend.requests, logs, dir)

        suspend fun stop() {
            head.stop()
            client.close()
            backend.stop()
        }
    }

    private fun provider(baseUrl: String, webSocket: Boolean, log: LogSink, replay: Boolean): CodexProvider {
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-token", "synthetic-account")
            override suspend fun refresh(): Credentials = credentials()
            override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
        }
        return CodexProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = HEAD, label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry(MODEL, "Luna", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = MODEL,
                auth = auth,
                locations = ProviderLocations(baseUrl = baseUrl),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, replay, "high", "detailed"),
            // No prefill round: this backend answers every frame with its step's answer, and a prefill frame must be
            // answered with none. A prefill frame carries the same client_metadata as the round after it
            // (ResponsesWsPrefiller.run reads the same turn snapshot), so leaving it out hides nothing here.
            quirks = CodexQuirks().defaultQuirks().withSummaryDelivery(null).withWebSocketToml(webSocket)
                .withWebSocketPrefillToml(false),
            log = log,
        )
    }
}
