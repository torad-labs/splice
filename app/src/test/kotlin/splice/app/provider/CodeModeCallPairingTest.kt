// Goal (fix 1 of the Oct 27 Responses fixes, artifacts/responses-gaps-2026-10-27/goals.md): a person using GPT through
// an openai-responses head never has a turn refused because splice sent a tool call without its result, and GPT never
// reads its own reasoning twice. After an interruption (an overload mid-stream, a re-anchor, a websocket round that
// falls back to HTTP, a compaction), the next request carries each call once, followed by its result, and each
// reasoning item once. Where a call's result was sent (the client's tool result, or a script's output), that result
// goes out again, never a stand-in; a call whose result never existed is answered the way Codex answers it.
//
// The shapes are the two refusals of Oct 26 and 27. At 04:55 CT an overload mid-stream re-anchored a code-mode turn,
// continuing from partial output; the rebuild kept 198 records as ordinary tool calls, and the next request carried
// each reasoning item twice and 11 wait calls with no result, refused over the websocket and again over HTTP with "No
// tool output found for function call". At 23:23 CT a compaction turn was refused the same way.
//
// These drive a codex head in code mode the way Claude Code does, through /v1/messages, in front of a scripted
// Responses backend that streams as GPT does (each reasoning item with the stream's empty content, a script that
// yields, a wait on it, a script that asks the client for a tool call) and a scripted code-mode runtime. Everything
// between them is the head's own. What they check is what the backend reads for every request: an HTTP body's input,
// and for a chained websocket frame the input of the response it continues, that response's output, then the frame's
// own input.
package splice.app.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.util.JsonScalars
import splice.dialect.responses.ReasoningSettings
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ClientRequest
import splice.head.scripted.ReplyScript
import splice.head.scripted.ScriptedHead
import splice.head.scripted.ScriptedHeads
import splice.head.scripted.ScriptedReply
import splice.head.scripted.Transport
import splice.head.scripted.reply
import splice.head.scripted.withScriptedHeads
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeWiring
import splice.provider.codex.CodexProvider
import splice.provider.codex.CodexQuirks
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.NativeCodeModeOptions
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A responses-lite model, the only kind code mode runs on, as the codex profile reads model ids. */
private const val CODE_MODE_MODEL = "gpt-6.1-sol"
private const val CONTEXT_WINDOW = 272_000L
private const val CLIENT_RESULT = "synthetic file contents"
private const val PARTIAL = "partial words"
private const val REST = " and the rest"
private const val SUMMARY = "synthetic summary of the conversation"

/** The words a head's re-anchor asks GPT to carry an interrupted answer on with, instead of starting it again. */
private const val REANCHOR_MARKER = "Your previous stream was interrupted mid-answer."

/** Claude Code's summarizer prompt and instruction, which mark a request as a compaction. */
private const val SUMMARIZER = "You are a helpful AI assistant tasked with summarizing conversations."
private const val SUMMARY_ASK =
    "Your task is to create a detailed summary of the conversation so far, paying close attention to the requests."

/** Codex's result for a call that never ran (codex-rs context_manager/normalize.rs). */
private const val ABORTED = "aborted"

/** The websocket close a backend sends when it fails a round before any output. */
private const val SERVER_ERROR_CLOSE = 1011
private const val PROPERTY_RUNS = 24
private const val MAX_TOOL_TURNS = 3
private const val LOG_LINES = 40

/** The call kinds a Responses input pairs with a result, and the result kind that answers each. */
private val ANSWERS = mapOf(
    "function_call" to "function_call_output",
    "local_shell_call" to "function_call_output",
    "custom_tool_call" to "custom_tool_call_output",
    "tool_search_call" to "tool_search_output",
)

private val TOOLS = buildJsonArray {
    addJsonObject {
        put("name", "Read")
        put("description", "Read a synthetic file")
        putJsonObject("input_schema") { put("type", "object") }
    }
}

class CodeModeCallPairingTest {

    @Test
    fun `an overload mid-stream that the head re-anchors leaves every call of a code-mode turn answered once`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadThenReanchor(tmp, webSocket = false) }

    @Test
    fun `an overload mid-stream on the websocket, carried on over HTTP, leaves every call answered once`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadThenReanchor(tmp, webSocket = true) }

    @Test
    fun `a compaction in the middle of a code-mode tool loop sends every call with its result over HTTP`(
        @TempDir tmp: Path,
    ) = runBlocking { compactionMidLoop(tmp, webSocket = false) }

    @Test
    fun `a compaction in the middle of a code-mode tool loop sends every call with its result over the websocket`(
        @TempDir tmp: Path,
    ) = runBlocking { compactionMidLoop(tmp, webSocket = true) }

    @Test
    fun `every request a code-mode head sends pairs each call with one result and each reasoning id once`(
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val gpt = Gpt()
            val ledger = Ledger(this, gpt)
            val http = CodeModeRig.start(this, tmp, ledger, webSocket = false)
            val socket = CodeModeRig.start(this, tmp, ledger, webSocket = true)
            val stories = mutableListOf<String>()
            repeat(PROPERTY_RUNS) { run ->
                val random = Random(run)
                val rig = if (random.nextBoolean()) socket else http
                stories += "run $run: " + GeneratedSession(rig, gpt, random, run).play()
            }
            ledger.assertPaired("generated sessions:\n${stories.joinToString("\n")}", http, socket)
        }
    }

    /** The 04:55 shape: a script that yields, a wait on it that asks the client to Read, the client's result, then GPT
     *  reasons and the backend overloads mid-stream. Over HTTP the head continues from the reasoning that arrived
     *  ("continuing from partial output"); on the websocket the round falls back to HTTP, as the 04:55 turn's did.
     *  Either way the next request is the head's own rebuild of the history. */
    private suspend fun overloadThenReanchor(tmp: Path, webSocket: Boolean) = withScriptedHeads(tmp) {
        val gpt = Gpt()
        val ledger = Ledger(this, gpt)
        val rig = CodeModeRig.start(this, tmp, ledger, webSocket)
        val talk = Conversation("synthetic-reanchor-session", "Start the synthetic code-mode task.")

        val first = rig.turn(talk, gpt.exec("cell:c1 yield read"), gpt.waitOn("c1"))
        talk.result(ledger.toolCall(talk, first, rig), CLIENT_RESULT)
        val overloaded = ledger.requestCount()
        val second = rig.turn(talk, gpt.overloadAfterReasoning(), gpt.answer(REST))

        // The person reads the answer, not the overload.
        assertEquals(REST, second.text, "the turn went on after the overload: ${second.describe()}")
        // The head carried the turn on: over HTTP from what arrived, on the websocket over HTTP.
        val next = ledger.reads().drop(overloaded + 1)
        assertTrue(
            next.any { if (webSocket) it.transport == Transport.HTTP else REANCHOR_MARKER in it.body },
            "the head carried the turn on after the overload: ${ledger.summary(rig)}",
        )
        ledger.assertPaired("the 04:55 shape over ${if (webSocket) "the websocket" else "HTTP"}", rig)
    }

    /** The 23:23 shape: Claude Code compacts between a tool call and its run, so the compaction carries the previous
     *  request with the summary instruction appended, and the answer with the new call is dropped. */
    private suspend fun compactionMidLoop(tmp: Path, webSocket: Boolean) = withScriptedHeads(tmp) {
        val gpt = Gpt()
        val ledger = Ledger(this, gpt)
        val rig = CodeModeRig.start(this, tmp, ledger, webSocket)
        val talk = Conversation("synthetic-compaction-session", "Start the synthetic compaction task.")

        val first = rig.turn(talk, gpt.exec("cell:c1 yield read"), gpt.waitOn("c1"))
        talk.result(ledger.toolCall(talk, first, rig), CLIENT_RESULT)
        val second = rig.turn(talk, gpt.exec("cell:c2 read"))
        assertEquals(
            1,
            Conversation.toolUses(Conversation.content(second)).size,
            "${second.describe()}\n${ledger.summary(rig)}",
        )
        talk.compact()
        val summary = rig.turn(talk, gpt.answer(SUMMARY))

        assertTrue(SUMMARY in summary.text, "the compaction was answered: ${summary.describe()}")
        assertTrue(ledger.reads().any { SUMMARY_ASK in it.body }, "the compaction reached the backend")
        ledger.assertPaired("the 23:23 shape over ${if (webSocket) "the websocket" else "HTTP"}", rig)
    }
}

/** One Claude Code conversation: its messages so far, as the client keeps and sends them. */
private class Conversation(val session: String, opening: String) {
    val messages = mutableListOf(message("user", JsonPrimitive(opening)))
    private var system: String? = null

    fun body(): JsonObject = buildJsonObject {
        put("messages", JsonArray(messages))
        put("tools", TOOLS)
        system?.let { put("system", it) }
    }

    /** Keeps the assistant's answer as the client does, and returns the ids of the tools it asks for. */
    fun answered(answer: ClientAnswer): List<String> {
        val content = content(answer)
        messages += message("assistant", content)
        return toolUses(content)
    }

    fun result(id: String, text: String) {
        messages += message(
            "user",
            buildJsonArray {
                addJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", text)
                }
            },
        )
    }

    fun user(text: String) {
        messages += message("user", JsonPrimitive(text))
    }

    /** Forgets the last answer and the tool result sent for it, as a compaction between the two does. */
    fun dropLastToolTurn() {
        repeat(2) { messages.removeAt(messages.lastIndex) }
    }

    /** Claude Code's compaction: the previous request again, with the summarizer prompt and its instruction. */
    fun compact() {
        system = SUMMARIZER
        user(SUMMARY_ASK)
    }

    private fun message(role: String, content: JsonElement): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    companion object {
        fun toolUses(content: JsonArray): List<String> = content.mapNotNull { block ->
            (block as? JsonObject)?.takeIf { JsonScalars.str(it, "type") == "tool_use" }
                ?.let { JsonScalars.str(it, "id") }
        }

        /** The assistant content the client assembles from the answer's stream, or reads from its JSON body. */
        fun content(answer: ClientAnswer): JsonArray {
            if (answer.events.isEmpty()) return body(answer)
            val blocks = sortedMapOf<Int, MutableMap<String, JsonElement>>()
            val inputs = HashMap<Int, StringBuilder>()
            answer.events.mapNotNull { event ->
                runCatching { Json.parseToJsonElement(event.data).jsonObject }.getOrNull()
            }.forEach { data ->
                val index = JsonScalars.int(data, "index")
                if (index != null) assemble(blocks, inputs, data, index)
            }
            inputs.forEach { (index, text) ->
                if (text.isNotBlank()) blocks[index]?.put("input", Json.parseToJsonElement(text.toString()))
            }
            return JsonArray(blocks.values.map { JsonObject(it) })
        }

        private fun body(answer: ClientAnswer): JsonArray =
            runCatching { Json.parseToJsonElement(answer.body).jsonObject["content"]?.jsonArray }
                .getOrNull() ?: JsonArray(emptyList())

        /** One stream event folded into the block it names: a start opens it, a delta grows its text or its input. */
        private fun assemble(
            blocks: MutableMap<Int, MutableMap<String, JsonElement>>,
            inputs: MutableMap<Int, StringBuilder>,
            data: JsonObject,
            index: Int,
        ) {
            when (JsonScalars.str(data, "type")) {
                "content_block_start" ->
                    (data["content_block"] as? JsonObject)?.let { blocks[index] = it.toMutableMap() }
                "content_block_delta" -> {
                    val block = blocks[index]
                    val delta = data["delta"] as? JsonObject
                    if (block != null && delta != null) grow(block, inputs, delta, index)
                }
            }
        }

        private fun grow(
            block: MutableMap<String, JsonElement>,
            inputs: MutableMap<Int, StringBuilder>,
            delta: JsonObject,
            index: Int,
        ) {
            when (JsonScalars.str(delta, "type")) {
                "text_delta" -> append(block, "text", JsonScalars.str(delta, "text"))
                "thinking_delta" -> append(block, "thinking", JsonScalars.str(delta, "thinking"))
                "signature_delta" -> append(block, "signature", JsonScalars.str(delta, "signature"))
                "input_json_delta" ->
                    inputs.getOrPut(index) { StringBuilder() }
                        .append(JsonScalars.str(delta, "partial_json").orEmpty())
            }
        }

        private fun append(block: MutableMap<String, JsonElement>, field: String, text: String?) {
            block[field] = JsonPrimitive(JsonScalars.str(block[field]).orEmpty() + text.orEmpty())
        }
    }
}

/** A scripted answer of GPT's, and the response id the backend keeps its output under (none when it gave none). */
private data class Answer(val reply: ScriptedReply, val id: String?)

/** GPT as the backend streams it: each answer its own response, whose output the backend keeps by id. Reasoning items
 *  carry the stream's empty content, as the backend sends them. */
private class Gpt {
    /** Each response's output as the backend holds it, by response id. */
    val outputs = ConcurrentHashMap<String, List<JsonObject>>()
    private var serial = 0

    /** Reasoning, then [source] written for exec. The runtime reads its cell, whether it yields and whether it asks
     *  the client to Read from the source's words. */
    fun exec(source: String): Answer = respond { n -> listOf(reasoning(n), execCall(n, source)) }

    fun waitOn(cell: String): Answer = respond { n -> listOf(reasoning(n), waitCall(n, cell)) }

    fun answer(text: String): Answer = respond { n -> listOf(reasoning(n), message(n, text)) }

    /** Reasoning and the first words of an answer, then the backend overloads before the answer completes. */
    fun overloadAfterText(text: String): Answer = fail { n -> listOf(reasoning(n), message(n, text)) to 1 }

    /** Reasoning, then the backend overloads before anything else. */
    fun overloadAfterReasoning(): Answer = fail { n -> listOf(reasoning(n)) to 1 }

    /** Reasoning, the words GPT writes before a script when [preface] is given, and the start of the script, then
     *  the backend overloads while the script is still being written. */
    fun overloadMidScript(source: String, preface: String? = null): Answer =
        fail { n -> listOfNotNull(reasoning(n), preface?.let { message(n, it) }, execCall(n, source)) to 1 }

    /** The backend overloads before any output. */
    fun overloadBeforeOutput(): Answer = fail { _ -> emptyList<JsonObject>() to 0 }

    /** A websocket round the backend closes before any output; the head answers it again over HTTP. */
    fun closeBeforeOutput(): Answer = Answer(reply { close(SERVER_ERROR_CLOSE, "synthetic server error") }, null)

    private fun respond(items: (Int) -> List<JsonObject>): Answer {
        val n = ++serial
        val id = "resp_synthetic_$n"
        val output = items(n)
        outputs[id] = output
        return Answer(
            reply {
                event(created(id))
                output.forEachIndexed { at, item -> stream(this, at, item, done = true) }
                event(
                    buildJsonObject {
                        put("type", "response.completed")
                        putJsonObject("response") {
                            put("id", id)
                            put("status", "completed")
                            put("output", JsonArray(output))
                            putJsonObject("usage") {
                                put("input_tokens", 11)
                                put("output_tokens", 3)
                            }
                        }
                    }.toString(),
                )
            },
            id,
        )
    }

    /** A response whose items before the last [open] complete, whose last [open] items only begin, and which then
     *  fails as an overload. */
    private fun fail(items: (Int) -> Pair<List<JsonObject>, Int>): Answer {
        val n = ++serial
        val id = "resp_synthetic_$n"
        val (output, open) = items(n)
        val done = output.dropLast(open)
        outputs[id] = done
        return Answer(
            reply {
                event(created(id))
                output.forEachIndexed { at, item -> stream(this, at, item, done = at < done.size) }
                event(
                    buildJsonObject {
                        put("type", "response.failed")
                        putJsonObject("response") {
                            put("id", id)
                            put("status", "failed")
                            put("output", JsonArray(done))
                            putJsonObject("error") {
                                put("code", "server_is_overloaded")
                                put("message", "Our servers are currently overloaded. Please try again later.")
                            }
                        }
                    }.toString(),
                )
            },
            id,
        )
    }

    /** One output item as the backend streams it: added, its text or source in one delta, and done when [done]. */
    private fun stream(script: ReplyScript, at: Int, item: JsonObject, done: Boolean) {
        val type = JsonScalars.str(item, "type")
        val begun = when (type) {
            "message" -> JsonObject(item + ("content" to JsonArray(emptyList())))
            "custom_tool_call" -> JsonObject(item + ("input" to JsonPrimitive("")))
            "reasoning" -> JsonObject(item - "encrypted_content" - "content")
            else -> item
        }
        script.event(itemEvent("response.output_item.added", at, begun))
        when (type) {
            "message" -> script.event(
                buildJsonObject {
                    put("type", "response.output_text.delta")
                    put("output_index", at)
                    put("content_index", 0)
                    put("item_id", checkNotNull(JsonScalars.str(item, "id")))
                    put("delta", text(item))
                }.toString(),
            )
            "custom_tool_call" -> script.event(
                buildJsonObject {
                    put("type", "response.custom_tool_call_input.delta")
                    put("output_index", at)
                    put("item_id", checkNotNull(JsonScalars.str(item, "id")))
                    put("delta", if (done) JsonScalars.str(item, "input").orEmpty() else "// the script was cut")
                }.toString(),
            )
        }
        if (done) script.event(itemEvent("response.output_item.done", at, item))
    }

    private fun text(item: JsonObject): String =
        (item["content"] as? JsonArray)?.joinToString("") { JsonScalars.str(it as? JsonObject, "text").orEmpty() }
            .orEmpty()

    private fun itemEvent(type: String, at: Int, item: JsonObject): String = buildJsonObject {
        put("type", type)
        put("output_index", at)
        put("item", item)
    }.toString()

    private fun created(id: String): String = buildJsonObject {
        put("type", "response.created")
        putJsonObject("response") {
            put("id", id)
            put("status", "in_progress")
            putJsonArray("output") {}
        }
    }.toString()

    private fun reasoning(n: Int): JsonObject = buildJsonObject {
        put("type", "reasoning")
        put("id", "rs_synthetic_$n")
        putJsonArray("summary") {}
        putJsonArray("content") {}
        put("encrypted_content", "synthetic-encrypted-$n")
    }

    private fun execCall(n: Int, source: String): JsonObject = buildJsonObject {
        put("type", "custom_tool_call")
        put("id", "ctc_synthetic_$n")
        put("call_id", "call_exec_$n")
        put("name", "exec")
        put("input", "// $source\nconst file = await tools.Read({});\ntext(file);")
    }

    private fun waitCall(n: Int, cell: String): JsonObject = buildJsonObject {
        put("type", "function_call")
        put("id", "fc_synthetic_$n")
        put("call_id", "call_wait_$n")
        put("name", "wait")
        put("arguments", """{"cell_id":"$cell","yield_time_ms":1000}""")
    }

    private fun message(n: Int, text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("id", "msg_synthetic_$n")
        put("role", "assistant")
        put("status", "completed")
        putJsonArray("content") {
            addJsonObject {
                put("type", "output_text")
                put("text", text)
                putJsonArray("annotations") {}
            }
        }
    }
}

/** What the backend read for one request: its whole input, and the body as it arrived. */
private data class Read(val at: Int, val transport: Transport, val input: List<JsonObject>, val body: String)

/** Every request the backend received, and the response that answered each, so a chained frame reads whole. */
private class Ledger(private val heads: ScriptedHeads, private val gpt: Gpt) {
    private val answeredBy = mutableListOf<String?>()

    fun script(answers: List<Answer>) {
        heads.provider.script(*answers.map { it.reply }.toTypedArray())
    }

    /** Notes which answer each request since [before] took: the next in order, the last one repeating. */
    fun took(before: Int, answers: List<Answer>) {
        val after = heads.provider.requests.size
        while (answeredBy.size < before) answeredBy += null
        (before until after).forEach { at -> answeredBy += answers[minOf(at - before, answers.lastIndex)].id }
    }

    fun requestCount(): Int = heads.provider.requests.size

    /** Keeps [answer] in [talk] and returns the one tool call it asks the client for. */
    fun toolCall(talk: Conversation, answer: ClientAnswer, rig: CodeModeRig): String =
        checkNotNull(talk.answered(answer).singleOrNull()) {
            "the head asked the client for no single tool: ${answer.describe()}\n${summary(rig)}"
        }

    /** What the backend read for each request, in arrival order. */
    fun reads(): List<Read> {
        val held = HashMap<String, List<JsonObject>>()
        return heads.provider.requests.mapIndexed { at, request ->
            val parsed = Json.parseToJsonElement(request.body).jsonObject
            val body = parsed["response"] as? JsonObject ?: parsed
            val own = (body["input"] as? JsonArray).orEmpty().map { it.jsonObject }
            val previous = JsonScalars.str(body, "previous_response_id")
            val whole = if (previous == null) {
                own
            } else {
                checkNotNull(held[previous]) { "request $at continues $previous, which the backend never answered" } +
                    own
            }
            answeredBy.getOrNull(at)?.let { id -> held[id] = whole + gpt.outputs[id].orEmpty() }
            Read(at, request.transport, whole, request.body)
        }
    }

    /** Each request sends every call once with a result after it, sends no result without its call, carries each
     *  reasoning id once, and never answers with a stand-in a call whose result was sent before. */
    fun assertPaired(context: String, vararg rigs: CodeModeRig) {
        val sent = HashSet<String>()
        val problems = reads().mapNotNull { read ->
            problems(read, sent).takeIf { it.isNotEmpty() }
                ?.let { "request ${read.at} (${read.transport}): ${it.joinToString("; ")}\n  ${shape(read.input)}" }
        }
        assertTrue(problems.isEmpty()) {
            "$context\n${problems.joinToString("\n")}\n${rigs.joinToString("\n") { summary(it) }}"
        }
    }

    fun summary(rig: CodeModeRig): String {
        val requests = reads().joinToString("\n") { "  ${it.at} ${it.transport}: ${shape(it.input)}" }
        val lines = rig.head.log.takeLast(LOG_LINES).joinToString("") { "  log: ${it.trimEnd()}\n" }
        return "requests:\n$requests\n$lines"
    }

    private fun problems(read: Read, sent: MutableSet<String>): List<String> {
        val items = read.input
        val problems = callProblems(items, sent) + resultProblems(items) + reasoningProblems(items)
        items.filter { type(it) in ANSWERS.values && it["output"] != JsonPrimitive(ABORTED) }
            .mapNotNullTo(sent) { callId(it) }
        return problems
    }

    /** Each call once, with a result after it, and never a stand-in where its result was sent before. */
    private fun callProblems(items: List<JsonObject>, sent: Set<String>): List<String> {
        val calls = items.withIndex().filter { ANSWERS.containsKey(type(it.value)) }
        val copies = calls.groupBy { type(it.value) to callId(it.value) }.filterValues { it.size > 1 }
            .map { (key, seen) -> "call ${key.second} goes out ${seen.size} times" }
        return copies + calls.mapNotNull { (at, call) -> answerProblem(items, at, call, sent) }
    }

    private fun answerProblem(items: List<JsonObject>, at: Int, call: JsonObject, sent: Set<String>): String? {
        val kind = ANSWERS.getValue(checkNotNull(type(call)))
        val id = callId(call)
        val result = items.drop(at + 1).firstOrNull { type(it) == kind && callId(it) == id }
            ?: return "call $id goes out with no result after it"
        return "call $id goes out with a stand-in where its result was sent before"
            .takeIf { result["output"] == JsonPrimitive(ABORTED) && id in sent }
    }

    /** No result goes out whose call the request does not carry, a server-side tool search aside. */
    private fun resultProblems(items: List<JsonObject>): List<String> =
        items.filter { type(it) in ANSWERS.values && !serverSearch(it) }.mapNotNull { result ->
            val owner = items.any { ANSWERS[type(it)] == type(result) && callId(it) == callId(result) }
            "result ${callId(result)} goes out without its call".takeUnless { owner }
        }

    private fun reasoningProblems(items: List<JsonObject>): List<String> =
        items.filter { type(it) == "reasoning" }.mapNotNull { JsonScalars.str(it, "id") }
            .groupingBy { it }.eachCount().filterValues { it > 1 }
            .map { (id, count) -> "reasoning $id goes out $count times" }

    /** The input as one line: each item's kind and the id that pairs or names it. */
    private fun shape(items: List<JsonObject>): String = items.joinToString(" ", transform = ::label)

    private fun label(item: JsonObject): String {
        val type = type(item) ?: return message(item)
        val aborted = if (item["output"] == JsonPrimitive(ABORTED)) "[aborted]" else ""
        return when {
            type == "reasoning" -> "R:${JsonScalars.str(item, "id")}"
            type in ANSWERS -> "${JsonScalars.str(item, "name") ?: type}(${callId(item)})"
            type in ANSWERS.values -> "=>${callId(item)}$aborted"
            type == "message" -> message(item)
            else -> type
        }
    }

    private fun message(item: JsonObject): String = "${JsonScalars.str(item, "role") ?: "?"}-msg"

    private fun type(item: JsonObject): String? = JsonScalars.str(item, "type")

    private fun callId(item: JsonObject): String? = JsonScalars.str(item, "call_id")

    private fun serverSearch(item: JsonObject): Boolean =
        type(item) == "tool_search_output" && JsonScalars.str(item, "execution") == "server"
}

/** A codex head in code mode over the scripted backend, with a scripted runtime under it. */
private class CodeModeRig(val head: ScriptedHead, private val ledger: Ledger, private val model: String) {

    /** One request of [talk], answered by [answers] in order, the last repeating. */
    suspend fun turn(talk: Conversation, vararg answers: Answer): ClientAnswer {
        val before = ledger.requestCount()
        ledger.script(answers.toList())
        val body = JsonObject(talk.body() + ("model" to JsonPrimitive(model)))
        val answer = head.client.send(ClientRequest(text = "", stream = true, session = talk.session, extra = body))
        ledger.took(before, answers.toList())
        return answer
    }

    companion object {
        suspend fun start(heads: ScriptedHeads, tmp: Path, ledger: Ledger, webSocket: Boolean): CodeModeRig {
            val kind = if (webSocket) "codex-ws" else "codex"
            var prefix = ""
            val head = heads.custom(kind) { tuning, log ->
                prefix = tuning.catalog.discoveryPrefix
                val bridge = CodexCodeModeBridge(
                    CodeModeBridgeConfig(
                        runtimes = { ScriptedRuntime() },
                        state = CodeModeStateLocation(tmp.resolve("records-$kind"), tmp.resolve("legacy-$kind.json")),
                        log = log,
                    ),
                )
                val quirks = CodexQuirks().defaultQuirks().let {
                    if (webSocket) it.withWebSocketToml(true).withWebSocketPrefillToml(false) else it
                }
                val catalog = ModelCatalog(
                    tuning.catalog.discoveryPrefix,
                    listOf(ModelEntry(CODE_MODE_MODEL, label = "Scripted GPT", contextWindow = CONTEXT_WINDOW)),
                    defaultContextWindow = CONTEXT_WINDOW,
                )
                CodexProvider(
                    tuning.copy(
                        catalog = catalog,
                        pinnedModel = CODE_MODE_MODEL,
                        locations = tuning.locations.copy(stateDir = tmp.resolve("state-$kind")),
                    ),
                    ReasoningSettings(ReasoningDisplay.OFF, true, "high", "detailed"),
                    quirks,
                    log = log,
                    codeMode = CodexCodeModeWiring(bridge, listOf(CODE_MODE_MODEL)),
                )
            }
            return CodeModeRig(head, ledger, "$prefix$CODE_MODE_MODEL")
        }
    }
}

/** A generated conversation: one to three scripts, each of which may yield and be waited on before it asks the client
 *  to Read, then GPT's answer; one round may be interrupted by an overload (before any output, after reasoning, while
 *  a script is written, or once the answer began), by a websocket close before output, or by a compaction. */
private class GeneratedSession(
    private val rig: CodeModeRig,
    private val gpt: Gpt,
    private val random: Random,
    private val run: Int,
) {
    private val talk = Conversation("synthetic-generated-$run", "Start synthetic generated task $run.")
    private val scripts = 1 + random.nextInt(MAX_TOOL_TURNS)
    private val interruptTurn = random.nextInt(scripts + 1)
    private val interruption = Interruption.entries[random.nextInt(Interruption.entries.size)]

    /** Plays the session and says what it did. */
    suspend fun play(): String {
        val story = mutableListOf("scripts=$scripts interrupt=$interruption@turn$interruptTurn")
        for (turn in 0..scripts) {
            if (compactsAt(turn)) return (story + compact()).joinToString(", ")
            val answers = if (turn < scripts) script(turn) else listOf(gpt.answer("synthetic answer $run"))
            val planned = if (turn == interruptTurn) interrupt(answers) else answers
            val answer = rig.turn(talk, *planned.toTypedArray())
            val ids = talk.answered(answer)
            if (turn >= scripts) {
                story += "answered: ${answer.text.isNotEmpty()} error: ${answer.error?.type}"
                continue
            }
            val id = ids.singleOrNull()
                ?: return (story + "turn $turn asked for no tool: ${answer.error?.type}").joinToString(", ")
            talk.result(id, "$CLIENT_RESULT $run-$turn")
        }
        return story.joinToString(", ")
    }

    /** A compaction needs a tool round behind it: it is what Claude Code sends between a call and its run. */
    private fun compactsAt(turn: Int): Boolean =
        turn > 0 && turn == interruptTurn && interruption == Interruption.COMPACTION

    /** Claude Code's compaction, which may drop the answer that asked for the tool it is waiting on. */
    private suspend fun compact(): List<String> {
        val dropped = random.nextBoolean()
        if (dropped) talk.dropLastToolTurn()
        talk.compact()
        val summary = rig.turn(talk, gpt.answer(SUMMARY))
        return listOf(
            "dropped the last tool turn: $dropped",
            "compacted: ${SUMMARY in summary.text} error: ${summary.error?.type}",
        )
    }

    /** GPT's answers for script [turn]: the script, then a wait on it when it yields. */
    private fun script(turn: Int): List<Answer> {
        val cell = "g$run-$turn"
        return if (random.nextBoolean()) {
            listOf(gpt.exec("cell:$cell yield read"), gpt.waitOn(cell))
        } else {
            listOf(gpt.exec("cell:$cell read"))
        }
    }

    private fun interrupt(answers: List<Answer>): List<Answer> {
        val round = random.nextInt(answers.size)
        val before = answers.take(round)
        val after = answers.drop(round)
        val failing = when (interruption) {
            Interruption.OVERLOAD_BEFORE_OUTPUT -> listOf(gpt.overloadBeforeOutput())
            Interruption.OVERLOAD_AFTER_REASONING -> listOf(gpt.overloadAfterReasoning())
            Interruption.OVERLOAD_MID_SCRIPT ->
                listOf(gpt.overloadMidScript("cell:cut read", preface = PARTIAL.takeIf { random.nextBoolean() }))
            Interruption.OVERLOAD_AFTER_TEXT -> listOf(gpt.overloadAfterText(PARTIAL))
            Interruption.CLOSE_BEFORE_OUTPUT -> listOf(gpt.closeBeforeOutput())
            Interruption.COMPACTION -> emptyList()
        }
        return before + failing + after
    }
}

private enum class Interruption {
    OVERLOAD_BEFORE_OUTPUT,
    OVERLOAD_AFTER_REASONING,
    OVERLOAD_MID_SCRIPT,
    OVERLOAD_AFTER_TEXT,
    CLOSE_BEFORE_OUTPUT,
    COMPACTION,
}

/** The code-mode runtime: a script's source names its cell, and says whether it yields before it asks the client to
 *  Read. A yielded script finishes once the client's result arrives. */
private class ScriptedRuntime : NativeCodeModeLifecycle {
    override suspend fun startNativeSession(
        sessionKey: String,
        source: String,
        tools: Set<String>,
        descriptions: Map<String, String>,
        options: NativeCodeModeOptions,
    ): NativeCodeModeCell {
        val cell = Regex("cell:(\\S+)").find(source)?.groupValues?.get(1) ?: "cell"
        val steps = buildList {
            if ("yield" in source) add(CodeModeStep.Yielded(cell, "started $cell"))
            if ("read" in source) {
                add(CodeModeStep.Calls(listOf(CodeModeCall("$cell-read", "Read", JsonObject(emptyMap())))))
            }
            add(CodeModeStep.Completed("finished $cell"))
        }
        return ScriptedCell(cell, ArrayDeque(steps))
    }

    override fun close() = Unit
}

private class ScriptedCell(override val cellId: String, private val steps: ArrayDeque<CodeModeStep>) :
    NativeCodeModeCell {
    override suspend fun submitResults(results: List<CodeModeResult>) = Unit

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep = next()

    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = next()

    override suspend fun terminate(): CodeModeStep = CodeModeStep.Completed("", terminated = true)

    override fun close() = Unit

    private fun next(): CodeModeStep = synchronized(steps) { steps.removeFirstOrNull() } ?: CodeModeStep.Completed("")
}
