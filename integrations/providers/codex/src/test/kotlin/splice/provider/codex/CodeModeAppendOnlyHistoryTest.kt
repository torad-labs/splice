// NEW: while Claude Code's history only grows, code mode sends GPT a history that only grows, the way Codex
// builds each request from the items it recorded (codex-rs core/src/session/turn.rs:1618). Live on 2026-10-23 a
// claudex seat abandoned a script on almost every turn from 15:51 CT ("native discovery history was edited:
// nativeOrder", 18 expected, 21 found), and every abandonment moved the prompt under the cache: the cached
// prefix sat at 107,776 tokens while the input grew to 131,922. The conversations here are generated: scripts of
// zero to three client steps, reasoning GPT returns and the client replays before the calls of the same message,
// a preface said before a script, which the client may join in one message with the prose GPT says after it when
// nothing came between them (a reanchored round streams into the text block the client already had), and plain
// answers followed by a new user message. Prefaces repeat, and a plain answer may open with the same words, so a
// script is told from the prose around it only by where it ran. After a running script's results the client may
// add a system notice, which the script runs on past, or the user may steer, which stops the script with the
// results it was given; either reaches GPT after the script's output. Between two requests splice may restart on
// the state it saved, as an install does, while a script waits on the client or not, and on a store an older build
// wrote, and GPT's next post still extends the last. A restart may also cut a reply the client never kept, and the
// user asks to go on: GPT is still shown the scripts that reply ran. The client keeps reasoning GPT gave after its
// preface in the order GPT streamed it, while GPT was sent its reasoning first. A rewound conversation cuts the
// client's history back to an earlier user message and edits it: that falls back to the client's history once and
// then only extends again, and GPT is still shown every script that ran before the cut, in the order it ran,
// whatever the client kept of it.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.ResponseShape
import splice.core.turn.RoundHandoffs
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.branch.CodeModeClearedCandidates
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

private const val RUNS = 120

/** Each sweep drives 120 or more generated conversations, so its wall time follows the machine, and the bound is a
 *  hang guard, never a speed check. On Oct 25 a sweep took 8.6 s, then past runTest's one-minute default at load 156
 *  on 16 threads, then past five minutes in the 11:38 PM CT pre-push at load 67 to 86, where all six sweeps ran 1 to
 *  15 times slower than the run before. A real hang still fails here. */
private val SWEEP_TIMEOUT = 30.minutes

/** Seeds a wider run broke under the draws below, kept so they stay fixed. A draw moved or added in a stream moves
 *  every seed after it to another conversation, so a new decision draws only in runs that did not exist before. */
private val KEPT = listOf(168, 209, 505, 1280, 1537)

/** Seeds a run of 2,000 broke on a store this build wrote, and on one a rollback wrote in part. */
private val KEPT_OWN = listOf(234, 505, 701)
private val KEPT_ROLLED_BACK = listOf(234, 471, 505, 701)
private const val REQUESTS = 128
private const val DEVELOPER = """{"role":"developer","content":"s"}"""
private const val TYPE = "type"
private const val CALL_ID = "call_id"
private const val EXEC_CALL = "custom_tool_call"
private const val EXEC_OUTPUT = "custom_tool_call_output"
private const val NEXT = "Next "
private const val STEER = "Steer "
private const val NOTICE = "Notice "
private val THROWN_AWAY = listOf("abandoned record", "history rewrite skipped")
private const val FAULTS = 0x5EED
private const val ANCHORS = "replayAnchors"
private const val CLIENT_START = "client"
private const val TRIMS = 0x7E1
private const val ROLE = "role"
private const val CONTENT = "content"
private const val OUTPUT = "output"

/** What Claude Code's microcompaction puts in place of an old tool result's content (claude-code
 *  src/services/compact/microCompact.ts, TIME_BASED_MC_CLEARED_MESSAGE). */
private const val CLEARED = "[Old tool result content cleared]"

class CodeModeAppendOnlyHistoryTest : CodeModeBridgeTestSupport() {

    @Test
    fun `an append-only conversation keeps every script and only ever extends what GPT was sent`() =
        runTest(timeout = SWEEP_TIMEOUT) {
            val failures = ((0 until RUNS) + KEPT).mapNotNull { seed -> Conversation(seed, rewinds = false).broke() }
            assertTrue(failures.isEmpty(), "${failures.size} conversations broke:\n" + failures.joinToString("\n"))
        }

    @Test
    fun `a rewound conversation falls back once, keeps every script, and then only extends again`() =
        runTest(timeout = SWEEP_TIMEOUT) {
            val failures = ((0 until RUNS) + KEPT).mapNotNull { seed -> Conversation(seed, rewinds = true).broke() }
            assertTrue(failures.isEmpty(), "${failures.size} conversations broke:\n" + failures.joinToString("\n"))
        }

    @Test
    fun `a conversation restarting on its own build's store keeps every script and only extends what GPT was sent`() =
        runTest(timeout = SWEEP_TIMEOUT) {
            val failures = ((0 until RUNS) + KEPT_OWN).mapNotNull { seed ->
                Conversation(seed, seed % 2 == 1, Store.OWN).broke()
            }
            assertTrue(failures.isEmpty(), "${failures.size} conversations broke:\n" + failures.joinToString("\n"))
        }

    @Test
    fun `a conversation restarting after a rollback keeps every script and only extends what GPT was sent`() =
        runTest(timeout = SWEEP_TIMEOUT) {
            val failures = ((0 until RUNS) + KEPT_ROLLED_BACK).mapNotNull { seed ->
                Conversation(seed, seed % 2 == 1, Store.ROLLED_BACK).broke()
            }
            assertTrue(failures.isEmpty(), "${failures.size} conversations broke:\n" + failures.joinToString("\n"))
        }

    @Test
    fun `a conversation hiding GPT's reasoning keeps every script and only extends what GPT was sent`() =
        runTest(timeout = SWEEP_TIMEOUT) {
            val failures = (0 until RUNS).mapNotNull { seed ->
                Conversation(seed, seed % 2 == 1, Store.entries[seed % Store.entries.size], reveals = false).broke()
            }
            assertTrue(failures.isEmpty(), "${failures.size} conversations broke:\n" + failures.joinToString("\n"))
        }

    @Test
    fun `a conversation whose old tool results are cleared keeps every script and only extends what GPT was sent`() =
        runTest(timeout = SWEEP_TIMEOUT) {
            val failures = (0 until RUNS).mapNotNull { seed ->
                Conversation(seed, seed % 2 == 1, Store.entries[seed % Store.entries.size], trims = true).broke()
            }
            assertTrue(failures.isEmpty(), "${failures.size} conversations broke:\n" + failures.joinToString("\n"))
        }

    /** Which build saved the state each restart starts on. */
    private enum class Store {
        /** At the first restart that cut no reply, a release's: its records kept no client start. */
        UPGRADED,

        /** Always this build's. A reply a restart cut may also be retried as it was, as Claude Code retries a
         *  stream that broke, with no user message after it. */
        OWN,

        /** This build's, but the records made between the first and the second restart were saved by a release the
         *  operator rolled back to, so the records of one conversation keep their client start or not by turns. */
        ROLLED_BACK,
    }

    /** One generated conversation, driven request by request the way Claude Code drives splice. */
    private inner class Conversation(
        private val seed: Int,
        private val rewinds: Boolean,
        private val store: Store = Store.UPGRADED,
        /** Whether Claude Code is shown GPT's reasoning: it is not while reasoning is hidden or not replayed. */
        private val reveals: Boolean = true,
        /** Whether Claude Code clears old tool results in place before a request, as its microcompaction does. */
        private val trims: Boolean = false,
    ) {
        private val random = Random(seed)

        /** What the client adds and when splice restarts, drawn apart so a seed's conversation stays the same. */
        private val extras = Random(seed.inv())

        /** What goes wrong around a request: a restart while a script waits, a reply the client never kept, a store
         *  an older build wrote, a reply the client kept in the order GPT streamed it. Drawn apart again. */
        private val faults = Random(seed xor FAULTS)

        /** When Claude Code clears old tool results, and how many recent ones it keeps. Drawn apart once more. */
        private val trimming = Random(seed xor TRIMS)
        private val scripts = ArrayDeque<ArrayDeque<CodeModeStep>>()
        private val stepCounts = mutableListOf<Int>()
        private val runtime = QueuedRuntime(scripts)
        private var manager = bridge(runtime, retention = CodeModeRetention(records = 64))
        private val logStart = logLines.size
        private val client = mutableListOf(DEVELOPER, Wire.user("Task $seed."))

        /** Every script GPT must be shown, in the order it ran, and the last item of [client] it ran after: the
         *  last of its reasoning and preface, or the item before it when it gave none. */
        private val outers = mutableListOf<String>()
        private val ranAfter = HashMap<String, Int>()

        /** Each script's preface, and the scripts whose reply the client never kept: GPT is sent their prefaces. */
        private val prefaces = HashMap<String, String>()
        private val lostCopies = mutableSetOf<String>()

        /** Every script that ran, a rewind or not, and how many had when splice first restarted. */
        private val started = mutableListOf<String>()
        private var restarts = 0
        private var startedBeforeRestart = 0

        /** Each script the user steered, with the results it was given, which its exec output must carry. */
        private val steered = HashMap<String, List<String>>()
        private val stopped = mutableSetOf<Int>()
        private var interjections = 0

        /** What happened between requests, by request, for a failure to name. */
        private val trace = mutableListOf<String>()
        private val upstreamTurns = if (rewinds) 4 + random.nextInt(6) else 2 + random.nextInt(7)

        /** The post GPT answers in plain text and after which the client rewinds, or -1. */
        private val rewindAt = if (rewinds) 1 + random.nextInt(upstreamTurns - 2) else -1
        private var results = emptyList<CodeModeResult>()
        private val submittedResults = mutableSetOf<String>()
        private var previous: List<JsonElement>? = null
        private var rewound = false

        /** Whether a reply was ever cut off, and whether the state was once saved by an older build. */
        private var cut = false
        private var aged = false
        private var posts = 0
        private var requests = 0

        /** Where this request's items start in [client]: prose said since may be joined in one message. */
        private var spoken = 0

        /** Null when the conversation held, else what broke first. */
        suspend fun broke(): String? = try {
            held()
        } finally {
            manager.onProviderStop()
        }

        private suspend fun held(): String? {
            while (posts < upstreamTurns) {
                check(requests++ < REQUESTS) { "seed $seed never finished" }
                request()?.let { return it }
            }
            val thrown = logLines.drop(logStart).filter { line -> THROWN_AWAY.any { it in line } }
            val short = runtime.cells.indices.filter { it !in stopped && runtime.cells[it].advances < stepCounts[it] }
            return when {
                thrown.isNotEmpty() -> "seed $seed threw a script's history away: ${thrown.first().take(240)}"
                short.isNotEmpty() -> "seed $seed: scripts $short never got all their results (${trace.joinToString()})"
                else -> null
            }
        }

        /** One client request and every post splice made for it. */
        private suspend fun request(): String? {
            val sink = RecordingSink()
            val exchanges = mutableListOf<Pair<List<JsonElement>, Reply>>()
            if (shouldTrim()) trim()
            // Claude Code sends every result in its history, and splice reads each one a script made.
            val given = if (trims) clientResults() else results
            val boundary = turn(seed, given)
            val prepared = if (trims) boundary.copy(rendering = clearedRendering()) else boundary
            manager.interceptor(prepared, disableParallel = false).intercept(Wire.body(client), sink) {
                val last = posts == upstreamTurns - 1 || posts == rewindAt
                val reply = answer(last)
                exchanges += Wire.input(it) to reply
                posts++
                RoundResult.Outcome(reply.outcome)
            }
            if (trims) submittedResults += given.map(CodeModeResult::id)
            val first = posts - exchanges.size
            spoken = client.size
            val ran = outers.size
            val broke = exchanges.withIndex()
                .firstNotNullOfOrNull { (index, exchange) -> post(first + index, exchange) }
            if (broke != null) return "$broke (${trace.joinToString()})"
            afterwards(sink.tools, ran)
            val stalled = exchanges.isEmpty() && sink.tools.isEmpty()
            return "seed $seed: request $requests went nowhere".takeIf { stalled }
        }

        private fun shouldTrim(): Boolean {
            if (!trims || requests <= 1) return false
            return trimming.nextInt(3) == 0
        }

        /** What follows splice's answer before the client's next request: the client runs the [tools] it was given
         *  and may add a notice or steer, and splice may restart while the script waits on them; with none to run
         *  the client may never have kept the reply, or rewind, and splice may restart. */
        private fun afterwards(tools: List<SeenTool>, ran: Int) {
            results = tools.map { CodeModeResult(it.id, "out-${it.id}") }
            client += tools.map { Wire.functionCall(it.id, it.name) }
            client += results.map { Wire.output(it.id, it.output) }
            if (tools.isNotEmpty()) {
                interject()
                if (faults.nextInt(4) == 0) {
                    // The script's process goes with splice: it stops with the results the client gives it, as a
                    // steered one does ("code-mode process state was lost; source was not rerun").
                    steered[outers.last()] = results.map(CodeModeResult::output)
                    stopped += stepCounts.lastIndex
                    trace += "waiting $requests"
                    restart()
                }
                return
            }
            if (posts < upstreamTurns && faults.nextInt(5) == 0) {
                lost(ran)
                return
            }
            val rewindNow = rewinds && !rewound && posts > rewindAt
            if (rewindNow) rewind()
            if (extras.nextInt(4) == 0) restart()
        }

        /** splice restarted while it streamed this request's reply, so the client kept none of it, and the user
         *  asks to go on. The scripts it ran from [ran] on still ran, right after the history the client sent. */
        private fun lost(ran: Int) {
            trace += "lost $requests"
            cut = true
            client.subList(spoken, client.size).clear()
            outers.drop(ran).forEach { ranAfter[it] = spoken - 1 }
            lostCopies += outers.drop(ran)
            if (store == Store.UPGRADED || faults.nextBoolean()) {
                client += Wire.user("Continue $requests.")
            } else {
                trace += "retried $requests"
            }
            restart()
        }

        /** Checks one post against the one before it, then gives the client GPT's answer to it. */
        private fun post(post: Int, exchange: Pair<List<JsonElement>, Reply>): String? {
            val (sent, answered) = exchange
            val broke = previous?.let { prefixBreak(seed, post, it, sent) }
                ?: codexShape(seed, post, sent, outers)
                ?: unstopped(seed, post, sent, steered)
                ?: proseOnce(post, sent)
            if (broke != null) return broke
            previous = sent
            keep(answered)
            answered.outer?.let {
                outers += it
                started += it
                ranAfter[it] = client.lastIndex
                answered.preface?.let { preface -> prefaces[it] = preface }
            }
            answered.text?.let { client += Wire.user("$NEXT$post.") }
            return null
        }

        /** What the client keeps of GPT's [answered] reasoning and prose: the reasoning first, or, about half the
         *  times GPT reasoned, said a script's preface, and reasoned again before the call, the order it streamed. */
        private fun keep(answered: Reply) {
            if (!reveals) {
                (answered.preface ?: answered.text)?.let { said(it, joins = true) }
                return
            }
            val streamed = streamedPreface(answered)
            if (streamed && faults.nextBoolean()) {
                client += Wire.reasoning(answered.reasoning.first())
                said(checkNotNull(answered.preface), joins = false)
                client += answered.reasoning.drop(1).map(Wire::reasoning)
            } else {
                client += answered.reasoning.map(Wire::reasoning)
                (answered.preface ?: answered.text)?.let { said(it, joins = answered.reasoning.isEmpty()) }
            }
        }

        private fun streamedPreface(answered: Reply): Boolean {
            if (answered.outer == null || answered.preface == null) return false
            return answered.reasoning.size > 1
        }

        /** Null when GPT is sent all the prose the client holds, and the preface of each script whose reply the
         *  client never kept, each once; else how much more or less GPT was sent. */
        private fun proseOnce(post: Int, sent: List<JsonElement>): String? {
            val held = client.sumOf { prose(Json.parseToJsonElement(it).jsonObject).length }
            val lost = outers.filter { it in lostCopies }.sumOf { prefaces[it]?.length ?: 0 }
            val given = sent.sumOf { prose(it.jsonObject).length }
            return "seed $seed, post $post: GPT was sent $given characters of prose, expected ${held + lost}"
                .takeIf { given != held + lost }
        }

        /** Every tool result in the client's history that a script's call was given, as Claude Code sends them. */
        private fun clientResults(): List<CodeModeResult> = client.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it.text(TYPE) == "function_call_output" }
            .filter { it.text(CALL_ID).startsWith(CODE_MODE_CLIENT_ID_PREFIX) }
            .map { CodeModeResult(it.text(CALL_ID), it.text(OUTPUT)) }

        /** This fixture bypasses the builder, so replay its typed callback boundary to identify candidates only.
         *  The production witness still has to prove their accepted bytes and branch; none is pre-verified. */
        private fun clearedRendering(): CodeModeResultRendering {
            val messages = client.map { Json.parseToJsonElement(it).jsonObject }
                .filter { it.text(TYPE) in setOf("function_call", "function_call_output") }.map { item ->
                    val call = item.text(TYPE) == "function_call"
                    buildJsonObject {
                        put(ROLE, if (call) "assistant" else "user")
                        put(
                            CONTENT,
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put(TYPE, if (call) "tool_use" else "tool_result")
                                        put(if (call) "id" else "tool_use_id", item.getValue(CALL_ID))
                                        if (call) {
                                            put("name", item.getValue("name"))
                                            put("input", Json.parseToJsonElement(item.text("arguments")))
                                        } else {
                                            put(
                                                CONTENT,
                                                JsonArray(
                                                    listOf(
                                                        buildJsonObject {
                                                            put(TYPE, "text"); put("text", item.getValue(OUTPUT))
                                                        },
                                                    ),
                                                ),
                                            )
                                        }
                                    },
                                ),
                            ),
                        )
                    }
                }
            val body = AnthropicParse.parseAnthropicBody(
                buildJsonObject {
                    put("model", "gpt-6-astra"); put("max_tokens", 100); put("messages", JsonArray(messages))
                }.toString(),
            )
            return CodeModeResultRendering(clearedCallbacks = CodeModeClearedCandidates.read(body))
        }

        /** Claude Code clears every tool result but its most recent few in place, keeping the call and its id. */
        private fun trim() {
            val outputs = client.indices.filter { "\"function_call_output\"" in client[it] }
            val kept = 1 + trimming.nextInt(3)
            val accepted = stateFiles.records().flatMap { it.getValue("results").jsonObject.keys }.toSet()
            // A pending result has never reached the gateway: erasing it would ask the oracle to invent bytes.
            val cleared = outputs.dropLast(kept).filter {
                val id = Json.parseToJsonElement(client[it]).jsonObject.text(CALL_ID)
                id in submittedResults && id in accepted && CLEARED !in client[it]
            }
            if (cleared.isEmpty()) return
            trace += "trim $requests"
            cleared.forEach { at ->
                client[at] = Wire.output(Json.parseToJsonElement(client[at]).jsonObject.text(CALL_ID), CLEARED)
            }
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

        /** What the client may add after a running script's results: a system notice, or the user steering, which
         *  stops the script with the results it was given. Either comes before GPT's next post, which may come after
         *  more of the script's steps, so each is numbered by that post and by how many came before it. */
        private fun interject() {
            when (extras.nextInt(8)) {
                0 -> client += Wire.notice("$NOTICE$posts-${interjections++}.")
                1 -> {
                    steered[outers.last()] = results.map(CodeModeResult::output)
                    stopped += stepCounts.lastIndex
                    client += Wire.user("$STEER$posts-${interjections++}.")
                }
            }
        }

        /** Stops splice and starts it again on the state it saved, as an install does, a script perhaps waiting on
         *  the client: what it kept in memory of GPT's posts is gone, and the next post still extends the last one.
         *  The [store] says which build saved it: after an upgrade, at the first restart the build it starts is newer
         *  than the one that saved the state, whose records kept no client start, as at an upgrade from a release;
         *  after a rollback, so is the build that ran between the first and second restart. Not after a cut reply:
         *  with no start kept, a script the
         *  client holds no copy of is left out (the older build had already left it out of what GPT was sent), a gap
         *  the goal names. The old splice ends as a process does, which a head stop alone does not: its timed sweep
         *  would keep it and all it loaded. */
        private fun restart() {
            trace += "restart $requests"
            manager.onProviderStop()
            if (++restarts == 1) startedBeforeRestart = started.size
            if (ages()) {
                aged = true
                trace += "older build"
                // Every record an upgrade starts on is the release's; after a rollback, only those made while it ran.
                val since = started.drop(startedBeforeRestart)
                stateFiles.rewriteRecords { record ->
                    if (store == Store.UPGRADED || record.ran(since)) unstarted(record) else record
                }
            }
            manager = bridge(runtime, retention = CodeModeRetention(records = 64))
        }

        /** Whether this restart starts on a store an older build wrote. */
        private fun ages(): Boolean = when (store) {
            Store.UPGRADED -> !cut && !aged
            Store.OWN -> false
            Store.ROLLED_BACK -> !cut && restarts == 2
        }

        /** Whether [this] is the record of one of the scripts [outers]. */
        private fun JsonObject.ran(outers: List<String>): Boolean = toString().let { saved ->
            outers.any { "\"$it\"" in saved }
        }

        /** [record] as a build that keeps no client start saved it. */
        private fun unstarted(record: JsonObject): JsonObject {
            val anchors = record[ANCHORS] as? JsonObject ?: return record
            return JsonObject(record + (ANCHORS to JsonObject(anchors - CLIENT_START)))
        }

        /** Cuts the client's history back to one of its user messages after the first and edits it. GPT's next post
         *  cannot extend the last one, so its prefix is not compared. A script that ran after an item the client
         *  kept ran in the history it kept, so GPT is still shown it; one that ran after the cut is gone. */
        private fun rewind() {
            trace += "rewind $requests"
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
            // Half of GPT's answers give no reasoning: the client keeps a preface of them at most, or nothing.
            val count = if (faults.nextBoolean()) 0 else 1 + random.nextInt(3)
            val reasoning = (0 until count).map { "rs-$seed-$posts-$it" }
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

    /** The first exec call or client message GPT is sent after one from later in the conversation, or null when
     *  they only go forward: a script ran after every message the client sent before it and before every one it
     *  sent after. Post n's exec call stands at 2n; a user message after post n's plain answer at 2n + 1; a
     *  notice or a steer the client added before post n at 2n - 1, in the order the client added them. */
    private fun misplaced(items: List<JsonObject>): String? {
        val ranks = items.mapNotNull { item ->
            val said = item.text("content")
            fun at(prefix: String) = said.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.removeSuffix(".")
            val added = at(STEER) ?: at(NOTICE)
            val rank = when {
                item.text(TYPE) == EXEC_CALL -> 2 * item.text(CALL_ID).substringAfterLast('-').toInt() to 0
                added != null -> 2 * added.substringBefore('-').toInt() - 1 to added.substringAfter('-').toInt()
                else -> at(NEXT)?.let { 2 * it.toInt() + 1 to 0 }
            }
            rank?.let { it to (item.text(CALL_ID).ifEmpty { said }) }
        }
        val order = compareBy<Pair<Int, Int>>({ it.first }, { it.second })
        val (before, after) = ranks.zipWithNext().firstOrNull { (a, b) -> order.compare(b.first, a.first) <= 0 }
            ?: return null
        return "${after.second} was sent after ${before.second}"
    }

    /** The first steered script whose exec output, once GPT is sent it, lacks a result the script was given. */
    private fun unstopped(seed: Int, post: Int, sent: List<JsonElement>, steered: Map<String, List<String>>): String? {
        val outputs = sent.map { it.jsonObject }.filter { it.text(TYPE) == EXEC_OUTPUT }
            .associate { it.text(CALL_ID) to it.text("output") }
        return steered.firstNotNullOfOrNull { (outer, given) ->
            val output = outputs[outer] ?: return@firstNotNullOfOrNull null
            given.firstOrNull { it !in output }?.let { "seed $seed, post $post: $outer stopped without $it" }
        }
    }

    private fun ids(items: List<JsonObject>, type: String) =
        items.filter { it.text(TYPE) == type }.map { it.text(CALL_ID) }

    private fun repeatedReasoning(items: List<JsonObject>): String? {
        val reasoning = items.filter { it.text(TYPE) == "reasoning" }.map { it.text("id") }
        return "a reasoning item was sent twice: $reasoning".takeIf { reasoning.size != reasoning.toSet().size }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    /** An assistant message's prose, or nothing for any other item. */
    private fun prose(item: JsonObject): String = if (item.text(ROLE) == "assistant") item.text(CONTENT) else ""

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

        /** A peer's message, a task notification or hook output, as a lite turn sends it. */
        fun notice(text: String) = """{"type":"message","role":"developer","content":"$text"}"""

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
