// NEW: while Claude Code's history only grows, code mode sends GPT a history that only grows, the way Codex
// builds each request from the items it recorded (codex-rs core/src/session/turn.rs:1618). Live on 2026-10-23 a
// claudex seat abandoned a script on almost every turn from 15:51 CT ("native discovery history was edited:
// nativeOrder", 18 expected, 21 found), and every abandonment moved the prompt under the cache: the cached
// prefix sat at 107,776 tokens while the input grew to 131,922. The conversations here are generated: scripts of
// zero to three client steps, reasoning GPT returns and the client replays before the calls of the same message,
// a preface said before a script, which the client may join in one message with the prose GPT says after it when
// nothing came between them (a reanchored round streams into the text block the client already had), and plain
// answers followed by a new user message. Prefaces repeat, and a plain answer may open with the same words, so a
// script is told from the prose around it only by where it ran. A rewound conversation cuts the client's history back to an
// earlier user message and edits it: that falls back to the client's history once and then only extends again, and
// GPT is still shown every script that ran before the cut, in the order it ran, whatever the client kept of it.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ResponseShape
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import kotlin.random.Random

private const val RUNS = 120

/** Seeds a wider run once broke, kept so they stay fixed. */
private val KEPT = listOf(249, 505, 908)
private const val REQUESTS = 128
private const val DEVELOPER = """{"role":"developer","content":"s"}"""
private const val TYPE = "type"
private const val CALL_ID = "call_id"
private const val EXEC_CALL = "custom_tool_call"
private const val EXEC_OUTPUT = "custom_tool_call_output"
private const val NEXT = "Next "
private val THROWN_AWAY = listOf("abandoned record", "history rewrite skipped")

class CodeModeAppendOnlyHistoryTest : CodeModeBridgeTestSupport() {

    @Test
    fun `an append-only conversation keeps every script and only ever extends what GPT was sent`() = runTest {
        val failures = ((0 until RUNS) + KEPT).mapNotNull { seed -> Conversation(seed, rewinds = false).broke() }
        assertTrue(failures.isEmpty(), "${failures.size} conversations broke:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `a rewound conversation falls back once, keeps every script, and then only extends again`() = runTest {
        val failures = ((0 until RUNS) + KEPT).mapNotNull { seed -> Conversation(seed, rewinds = true).broke() }
        assertTrue(failures.isEmpty(), "${failures.size} conversations broke:\n" + failures.joinToString("\n"))
    }

    /** One generated conversation, driven request by request the way Claude Code drives splice. */
    private inner class Conversation(private val seed: Int, private val rewinds: Boolean) {
        private val random = Random(seed)
        private val scripts = ArrayDeque<ArrayDeque<CodeModeStep>>()
        private val stepCounts = mutableListOf<Int>()
        private val runtime = QueuedRuntime(scripts)
        private val manager = bridge(runtime, retention = CodeModeRetention(records = 64))
        private val logStart = logLines.size
        private val client = mutableListOf(DEVELOPER, Wire.user("Task $seed."))

        /** Every script GPT must be shown, in the order it ran, and the last item of [client] it ran after: the
         *  last of its reasoning and preface, or the item before it when it gave none. */
        private val outers = mutableListOf<String>()
        private val ranAfter = HashMap<String, Int>()
        private val upstreamTurns = if (rewinds) 4 + random.nextInt(6) else 2 + random.nextInt(7)

        /** The post GPT answers in plain text and after which the client rewinds, or -1. */
        private val rewindAt = if (rewinds) 1 + random.nextInt(upstreamTurns - 2) else -1
        private var results = emptyList<CodeModeResult>()
        private var previous: List<JsonElement>? = null
        private var rewound = false
        private var posts = 0
        private var requests = 0

        /** Where this request's items start in [client]: prose said since may be joined in one message. */
        private var spoken = 0

        /** Null when the conversation held, else what broke first. */
        suspend fun broke(): String? {
            while (posts < upstreamTurns) {
                check(requests++ < REQUESTS) { "seed $seed never finished" }
                request()?.let { return it }
            }
            val thrown = logLines.drop(logStart).filter { line -> THROWN_AWAY.any { it in line } }
            val short = runtime.cells.indices.filter { runtime.cells[it].advances < stepCounts[it] }
            return when {
                thrown.isNotEmpty() -> "seed $seed threw a script's history away: ${thrown.first().take(240)}"
                short.isNotEmpty() -> "seed $seed: scripts $short never got all their results"
                else -> null
            }
        }

        /** One client request and every post splice made for it. */
        private suspend fun request(): String? {
            val sink = RecordingSink()
            val exchanges = mutableListOf<Pair<List<JsonElement>, Reply>>()
            manager.interceptor(turn(seed, results), disableParallel = false).intercept(Wire.body(client), sink) {
                val last = posts == upstreamTurns - 1 || posts == rewindAt
                val reply = answer(last)
                exchanges += Wire.input(it) to reply
                posts++
                RoundResult.Outcome(reply.outcome)
            }
            val first = posts - exchanges.size
            spoken = client.size
            val broke = exchanges.withIndex()
                .firstNotNullOfOrNull { (index, exchange) -> post(first + index, exchange) }
            results = sink.tools.map { CodeModeResult(it.id, "out-${it.id}") }
            client += sink.tools.map { Wire.functionCall(it.id, it.name) }
            client += results.map { Wire.output(it.id, it.output) }
            val rewindNow = rewinds && !rewound && posts > rewindAt && sink.tools.isEmpty()
            if (rewindNow) rewind()
            val stalled = exchanges.isEmpty() && sink.tools.isEmpty()
            return broke ?: "seed $seed: request $requests went nowhere".takeIf { stalled }
        }

        /** Checks one post against the one before it, then gives the client GPT's answer to it. */
        private fun post(post: Int, exchange: Pair<List<JsonElement>, Reply>): String? {
            val (sent, answered) = exchange
            val broke = previous?.let { prefixBreak(seed, post, it, sent) }
                ?: codexShape(seed, post, sent, outers)
            if (broke != null) return broke
            previous = sent
            client += answered.reasoning.map(Wire::reasoning)
            (answered.preface ?: answered.text)?.let { said(it, joins = answered.reasoning.isEmpty()) }
            answered.outer?.let {
                outers += it
                ranAfter[it] = client.lastIndex
            }
            answered.text?.let { client += Wire.user("$NEXT$post.") }
            return null
        }

        /** GPT's prose as the client keeps it: a message of its own, or, when it [joins] prose said earlier in this
         *  request with nothing between them, the same message, as Claude Code keeps text streamed into one block. */
        private fun said(text: String, joins: Boolean) {
            val last = client.lastOrNull()?.takeIf { client.size > spoken && joins && random.nextBoolean() }
            val before = last?.let { Json.parseToJsonElement(it).jsonObject }?.takeIf { it.text("role") == "assistant" }
            if (before == null) {
                client += Wire.assistant(text)
            } else {
                client[client.lastIndex] = Wire.assistant(before.text("content") + text)
            }
        }

        /** Cuts the client's history back to one of its user messages after the first and edits it. GPT's next post
         *  cannot extend the last one, so its prefix is not compared. A script that ran after an item the client
         *  kept ran in the history it kept, so GPT is still shown it; one that ran after the cut is gone. */
        private fun rewind() {
            val nexts = client.indices.filter { NEXT in client[it] }
            val cut = nexts[random.nextInt(nexts.size)]
            client.subList(cut, client.size).clear()
            outers.retainAll { ranAfter.getValue(it) < cut }
            client += Wire.user("Edited $seed.")
            rewound = true
            previous = null
        }

        /** GPT's answer to the post it was sent: a script with reasoning, or a plain answer, which [last] always is. */
        private fun answer(last: Boolean): Reply {
            val reasoning = (0 until random.nextInt(4)).map { "rs-$seed-$posts-$it" }
            val envelopes = reasoning.map(Wire::envelope)
            if (last || random.nextInt(10) < 3) {
                val text = "${preface().takeIf { random.nextInt(3) == 0 }.orEmpty()}Answer $posts."
                return Reply(
                    TurnOutcome.Success(
                        false,
                        false,
                        Usage(),
                        text = RoundText(bodyText = text, emittedText = true),
                        handoffs = RoundHandoffs(reasoningEnvelopes = envelopes),
                        shape = ResponseShape(messageClosed = true),
                    ),
                    reasoning,
                    text,
                    null,
                    null,
                )
            }
            // A script may make no client call at all: it finishes inside the request that started it.
            val steps = (0 until random.nextInt(4)).map { step ->
                val calls = (0 until 1 + random.nextInt(2)).map { if (it == 0) "Read" else "Edit" }
                CodeModeStep.Calls(calls.mapIndexed { index, name -> call("runtime-$posts-$step-$index", name) })
            }
            scripts += ArrayDeque<CodeModeStep>(steps + CodeModeStep.Completed("script $posts done"))
            stepCounts += steps.size + 1
            val preface = preface().takeIf { random.nextBoolean() }
            val outer = outerOutcome("outer-$seed-$posts")
            val handoffs = outer.handoffs.copy(reasoningEnvelopes = envelopes)
            val said = preface?.let { RoundText(bodyText = it, emittedText = true) } ?: outer.text
            return Reply(outer.copy(handoffs = handoffs, text = said), reasoning, null, "outer-$seed-$posts", preface)
        }

        private fun preface() = "Preface ${random.nextInt(2)}. "
    }

    /** GPT's answer: the outcome splice is given, the reasoning and prose the client replays, and the exec call. */
    private data class Reply(
        val outcome: TurnOutcome,
        val reasoning: List<String>,
        val text: String?,
        val outer: String?,
        val preface: String?,
    )

    /** Where [after] stops starting with [before], as GPT's prompt cache sees it. */
    private fun prefixBreak(seed: Int, post: Int, before: List<JsonElement>, after: List<JsonElement>): String? {
        val at = before.indices.firstOrNull { it >= after.size || before[it] != after[it] } ?: return null
        return "seed $seed, post $post: item $at of ${before.size} changed: " +
            "${before[at].toString().take(120)} became ${after.getOrNull(at)?.toString()?.take(120)}"
    }

    /** What Codex would show GPT: no call a script made through the client, each reasoning item once, and each exec
     *  call followed by its output, once each, for every one of the [outers], in the order they ran, between the
     *  client's messages it ran between. */
    private fun codexShape(seed: Int, post: Int, sent: List<JsonElement>, outers: List<String>): String? {
        val items = sent.map { it.jsonObject }
        val problem = nestedCall(items) ?: execPairs(items, outers) ?: misplaced(items) ?: repeatedReasoning(items)
        return problem?.let { "seed $seed, post $post: $it" }
    }

    private fun nestedCall(items: List<JsonObject>): String? =
        items.firstOrNull { it.text(CALL_ID).startsWith(CODE_MODE_CLIENT_ID_PREFIX) }
            ?.let { "GPT was sent a client call a script made: $it" }

    private fun execPairs(items: List<JsonObject>, outers: List<String>): String? {
        val calls = ids(items, EXEC_CALL)
        val outputs = ids(items, EXEC_OUTPUT)
        return when {
            calls != outers -> "exec calls $calls, expected $outers"
            outputs != calls || calls.size != calls.toSet().size -> "exec calls $calls with outputs $outputs"
            else -> lateOutput(items, calls)
        }
    }

    private fun lateOutput(items: List<JsonObject>, calls: List<String>): String? {
        fun at(type: String, id: String) = items.indexOfFirst { it.text(TYPE) == type && it.text(CALL_ID) == id }
        val late = calls.firstOrNull { at(EXEC_OUTPUT, it) < at(EXEC_CALL, it) } ?: return null
        return "the output of $late comes before its call"
    }

    /** The first exec call or user message GPT is sent after one from a later post, or null when the posts they
     *  came from only increase: a script ran after every message the client sent before it and before every one
     *  it sent after. */
    private fun misplaced(items: List<JsonObject>): String? {
        val posts = items.mapNotNull { item ->
            val said = item.text("content").takeIf { item.text("role") == "user" && it.startsWith(NEXT) }
            when {
                item.text(TYPE) == EXEC_CALL -> item.text(CALL_ID).substringAfterLast('-').toInt() to item.text(CALL_ID)
                said != null -> said.removePrefix(NEXT).removeSuffix(".").toInt() to said
                else -> null
            }
        }
        val (before, after) = posts.zipWithNext().firstOrNull { (a, b) -> b.first <= a.first } ?: return null
        return "${after.second} was sent after ${before.second}"
    }

    private fun ids(items: List<JsonObject>, type: String) =
        items.filter { it.text(TYPE) == type }.map { it.text(CALL_ID) }

    private fun repeatedReasoning(items: List<JsonObject>): String? {
        val reasoning = items.filter { it.text(TYPE) == "reasoning" }.map { it.text("id") }
        return "a reasoning item was sent twice: $reasoning".takeIf { reasoning.size != reasoning.toSet().size }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun turn(seed: Int, results: List<CodeModeResult>) = CodexCodeModeBridge.Turn(
        sessionId = "session-$seed",
        conversationKey = "conversation-$seed",
        model = "gpt-6-astra",
        tools = setOf("Read", "Edit"),
        toolResults = results,
    )

    /** The Responses items the generated client sends, and what splice posted. */
    private object Wire {
        fun input(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray.toList()

        fun body(items: List<String>) = """{"input":[${items.joinToString(",")}]}"""

        fun user(text: String) = """{"role":"user","content":"$text"}"""

        fun assistant(text: String) = """{"role":"assistant","content":"$text"}"""

        fun reasoning(id: String) = """{"type":"reasoning","id":"$id","summary":[],"encrypted_content":"e-$id"}"""

        fun functionCall(id: String, name: String) =
            """{"type":"function_call","call_id":"$id","name":"$name","arguments":"{}"}"""

        fun output(id: String, text: String) = """{"type":"function_call_output","call_id":"$id","output":"$text"}"""

        fun envelope(id: String): String = checkNotNull(
            ReasoningReplay.encodeReasoningEnvelope(
                buildJsonObject {
                    put("type", "reasoning")
                    put("id", id)
                    put("encrypted_content", "e-$id")
                },
            ),
        )
    }
}
