// Goal (fix 2 of the Oct 27 Responses fixes, artifacts/responses-gaps-2026-10-27/goals.md): a person using GPT in code
// mode keeps GPT's own earlier scripts and their results in its history, as GPT wrote them, for the life of the
// conversation. Splice falls back to Claude Code's plain tool calls only for a part of the history Claude Code itself
// changed, never because splice lost track of where a record goes.
//
// On Oct 26 and 27 the head logged 1,144 scripts in 11 bursts as staying "in the history as ordinary tool calls", each
// burst in a request it rebuilt after an interruption: a re-anchor that continued from partial output, or a compaction
// whose stream dropped. The fix-2 report classifies every reason those lines gave.
//
// These drive a codex head in code mode the way Claude Code does, through /v1/messages, in front of the scripted
// Responses backend and runtime of CodeModeScriptedConversation.kt. What they check is what the backend reads: each
// script GPT wrote earlier goes out as GPT wrote it, in its place (GPT's reasoning, its exec and its wait, each call
// with its result, the last of which says the script finished), and never as the client's plain tool call, unless
// Claude Code changed that script's part of its history. Then that part goes out as Claude Code sent it.
package splice.app.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.head.scripted.ClientAnswer
import splice.head.scripted.ScriptedHeads
import splice.head.scripted.withScriptedHeads
import java.nio.file.Path
import kotlin.random.Random

private const val FILE_TEXT = "synthetic file contents"
private const val CHANGED_TEXT = "synthetic file contents as the client now sends them"

/** What Claude Code's context management leaves in place of an old tool result it clears. */
private const val CLEARED = "[Old tool result content cleared]"
private const val GOING_ON = "synthetic words after the interruption"
private const val CUT_WORDS = "partial words"
private const val SUMMARY_TEXT = "synthetic summary of the conversation"
private const val OPENING = "Start the synthetic code-mode task."
private const val FIRST_REPLY = "synthetic first answer"
private const val SECOND_PROMPT = "Go on with the synthetic task."
private const val SECOND_REPLY = "synthetic second answer"
private const val THIRD_PROMPT = "Finish the synthetic task."

/** What the head logs when a re-anchor carries the round on from what arrived, as the 04:55 turn's did. */
private const val CONTINUED_FROM_PARTIAL = "continuing from partial output"

/** What the head logs when a re-anchor sends the round again whole, as the 03:41 turn's did. */
private const val RESTARTED = "restarting the round from scratch"

/** What the head logs when it re-anchors a round, whichever way it carries the round on. */
private const val REANCHORED = "re-anchor 1: "

/** What the head logs when it retries a round of a model it runs round by round, as the 22:16 turn's did. */
private const val FOLD_RETRIED = "fold re-anchor 1"

/** A responses-lite model whose reasoning the backend cuts, so the head runs its turns round by round. */
private const val CUT_MODEL = "gpt-5.6-luna"

/** What GPT reads as a script's result when the head stopped the script instead of letting it finish. */
private const val STOPPED = "Script terminated"
private const val GENERATED_RUNS = 200
private const val MOST_SCRIPTS = 3

/** The result kind that answers each call kind GPT writes in code mode. */
private val RESULT_KINDS = mapOf(
    "custom_tool_call" to "custom_tool_call_output",
    "function_call" to "function_call_output",
)

class CodeModeHistoryInPlaceTest {

    @Test
    fun `an overload mid-stream that the head re-anchors keeps GPT's earlier scripts in place over HTTP`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadKeepsScripts(tmp, webSocket = false) }

    @Test
    fun `an overload mid-stream on the websocket keeps GPT's earlier scripts in place`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadKeepsScripts(tmp, webSocket = true) }

    @Test
    fun `an overload after GPT's reasoning, past scripts that asked the client at once, keeps them in place`(
        @TempDir tmp: Path,
    ) = runBlocking { overloadKeepsScripts(tmp, webSocket = false, yields = false) }

    @Test
    fun `an overload before any output, which the head sends again whole, keeps GPT's scripts in place over HTTP`(
        @TempDir tmp: Path,
    ) = runBlocking { restartKeepsScripts(tmp, webSocket = false) }

    @Test
    fun `an overload before any output, which the head sends again whole, keeps GPT's scripts in place on the socket`(
        @TempDir tmp: Path,
    ) = runBlocking { restartKeepsScripts(tmp, webSocket = true) }

    @Test
    fun `an overload mid-round that the head retries on a model it runs round by round keeps GPT's scripts in place`(
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val session = InPlaceSession.start(this, tmp, webSocket = false, "synthetic-in-place-fold", CUT_MODEL)
            val (first, second) = session.twoPrompts()
            val gpt = session.gpt
            val overloaded = session.requests()
            val last = session.rig.turn(session.talk, gpt.overloadAfterReasoning(), gpt.answer(GOING_ON))

            val context = "the 22:16 shape, a round the head retries on $CUT_MODEL"
            assertTrue(GOING_ON in last.text) { "the turn went on: ${last.describe()}\n${session.summary()}" }
            session.ledger.assertLogged(session.rig, FOLD_RETRIED, context)
            session.assertInPlace(context, overloaded, listOf(first, second))
        }
    }

    @Test
    fun `a compaction whose stream drops keeps GPT's earlier scripts in place over HTTP`(
        @TempDir tmp: Path,
    ) = runBlocking { droppedCompactionKeepsScripts(tmp, webSocket = false) }

    @Test
    fun `a compaction whose stream drops keeps GPT's earlier scripts in place on the websocket`(
        @TempDir tmp: Path,
    ) = runBlocking { droppedCompactionKeepsScripts(tmp, webSocket = true) }

    @Test
    fun `a script waiting on the client's tool result finishes on it, and GPT reads that it finished`(
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val session = InPlaceSession.start(this, tmp, webSocket = false, "synthetic-in-place-parked")
            val script = session.script("c1", yields = true)
            val resumed = session.requests()
            session.answer(FIRST_REPLY)
            session.assertInPlace("the requests after the client's tool result", resumed, listOf(script))
        }
    }

    @Test
    fun `a tool result Claude Code changed before a new prompt falls back, and the other scripts keep their records`(
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val session = InPlaceSession.start(this, tmp, webSocket = false, "synthetic-in-place-changed-prompt")
            val (first, second) = session.twoPrompts(yields = false)
            session.answer(SECOND_REPLY)
            session.talk.changeResult(first.toolUse, CHANGED_TEXT)
            session.user(THIRD_PROMPT)
            val changed = session.requests()
            val gpt = session.gpt
            val last = session.rig.turn(session.talk, gpt.overloadAfterReasoning(), gpt.answer(GOING_ON))

            val context = "a tool result changed before a new prompt, then an overload the head re-anchors"
            assertTrue(GOING_ON in last.text) { "the turn went on: ${last.describe()}\n${session.summary()}" }
            session.ledger.assertLogged(session.rig, REANCHORED, context)
            session.assertInPlace(context, changed, listOf(second), mapOf(first to CHANGED_TEXT))
        }
    }

    @Test
    fun `a result Claude Code edits as a waiting script's result arrives falls back, and the script finishes`(
        @TempDir tmp: Path,
    ) = runBlocking { changedWithWaitingScript(tmp, CHANGED_TEXT) }

    @Test
    fun `a result Claude Code clears as a waiting script's result arrives falls back, and the script finishes`(
        @TempDir tmp: Path,
    ) = runBlocking { changedWithWaitingScript(tmp, CLEARED) }

    @Test
    fun `generated conversations keep every script GPT wrote in place unless Claude Code changed its part`(
        @TempDir tmp: Path,
    ) = runBlocking {
        withScriptedHeads(tmp) {
            val stories = mutableListOf<String>()
            val problems = mutableListOf<String>()
            val gpt = ScriptedGpt()
            val ledger = BackendLedger(this, gpt)
            val http = CodeModeRig.start(this, tmp, ledger, webSocket = false)
            val socket = CodeModeRig.start(this, tmp, ledger, webSocket = true)
            repeat(GENERATED_RUNS) { run ->
                val random = Random(run)
                val rig = if (random.nextBoolean()) socket else http
                val talk = CodeModeConversation("synthetic-in-place-$run", "$OPENING $run")
                val session = InPlaceSession(rig, ledger, gpt, talk)
                val generated = GeneratedHistory(session, random, run)
                stories += "run $run: " + generated.play()
                problems += generated.problems.map { "run $run: $it" }
            }
            assertTrue(problems.isEmpty()) {
                "${problems.joinToString("\n")}\n${stories.joinToString("\n")}\n${ledger.summary(http)}"
            }
        }
    }

    /** Past the two prompts, the second script waits on the client's Read. Claude Code changes the first script's
     *  result to [text] in the request that carries the Read's result. The first script falls back to its plain tool
     *  call with [text], and the waiting script finishes on its result and stays as GPT wrote it. */
    private suspend fun changedWithWaitingScript(tmp: Path, text: String) = withScriptedHeads(tmp) {
        val session = InPlaceSession.start(this, tmp, webSocket = false, "synthetic-in-place-changed-waiting")
        val (first, second) = session.twoPrompts()
        session.talk.changeResult(first.toolUse, text)
        val changed = session.requests()
        session.answer(GOING_ON)

        val context = "a result changed as a waiting script's result arrives"
        session.assertInPlace(context, changed, listOf(second), mapOf(first to text))
    }

    /** The 04:55 shape: GPT answered one prompt after a script and a wait on it, the next prompt ran a script and a
     *  wait that asked the client to Read, and the backend overloads mid-stream after GPT's reasoning. The head carries
     *  the round on from what arrived, over its own rebuild of the history. On the websocket the first overload sends
     *  the round over HTTP, where the second one re-anchors it. */
    private suspend fun overloadKeepsScripts(tmp: Path, webSocket: Boolean, yields: Boolean = true) =
        withScriptedHeads(tmp) {
            val session = InPlaceSession.start(this, tmp, webSocket, "synthetic-in-place-overload")
            val (first, second) = session.twoPrompts(yields)
            val gpt = session.gpt
            val answers = listOfNotNull(
                gpt.overloadAfterReasoning().takeIf { webSocket },
                gpt.overloadAfterReasoning(),
                gpt.answer(GOING_ON),
            )
            val overloaded = session.requests()
            val last = session.rig.turn(session.talk, *answers.toTypedArray())

            val scripts = if (yields) "scripts that yielded" else "scripts that asked the client at once"
            val context = "the 04:55 shape over ${if (webSocket) "the websocket" else "HTTP"}, past $scripts"
            assertTrue(GOING_ON in last.text) { "the turn went on: ${last.describe()}\n${session.summary()}" }
            session.ledger.assertLogged(session.rig, CONTINUED_FROM_PARTIAL, context)
            session.assertInPlace(context, overloaded, listOf(first, second))
        }

    /** The 03:41 shape, which 21:40, 22:54, 03:29 and 05:29 share: past the two prompts, each answered by a script
     *  that asked the client to Read at once, GPT answers the second script's result in words and the person asks
     *  again. The backend overloads that round before any output, so nothing of it reached the client, and the head
     *  sends the round again whole over its own rebuild of the history. On the websocket the first overload sends the
     *  round over HTTP, where the second one restarts it. */
    private suspend fun restartKeepsScripts(tmp: Path, webSocket: Boolean) = withScriptedHeads(tmp) {
        val session = InPlaceSession.start(this, tmp, webSocket, "synthetic-in-place-restart")
        val (first, second) = session.twoPrompts(yields = false)
        session.answer(SECOND_REPLY)
        session.user(THIRD_PROMPT)
        val gpt = session.gpt
        val answers = listOfNotNull(
            gpt.overloadBeforeOutput().takeIf { webSocket },
            gpt.overloadBeforeOutput(),
            gpt.answer(GOING_ON),
        )
        val restarted = session.requests()
        val last = session.rig.turn(session.talk, *answers.toTypedArray())

        val context = "the 03:41 shape over ${if (webSocket) "the websocket" else "HTTP"}"
        assertTrue(GOING_ON in last.text) { "the turn went on: ${last.describe()}\n${session.summary()}" }
        session.ledger.assertLogged(session.rig, RESTARTED, context)
        session.assertInPlace(context, restarted, listOf(first, second))
    }

    /** The 23:23 shape: past the same two prompts, Claude Code compacts between a tool call and its run, so the
     *  compaction carries the previous request with the summary instruction appended, and its stream drops once the
     *  summary began, with no close frame on the websocket. The head takes the round up again over its own rebuild.
     *  Claude Code dropped the third script's turn, so GPT's call that asked the client for it does not go out. */
    private suspend fun droppedCompactionKeepsScripts(tmp: Path, webSocket: Boolean) = withScriptedHeads(tmp) {
        val session = InPlaceSession.start(this, tmp, webSocket, "synthetic-in-place-compaction")
        val (first, second) = session.twoPrompts()
        val third = session.gpt.exec("cell:c3 read")
        val dropped = session.rig.turn(session.talk, third)
        val asked = CodeModeConversation.toolUses(CodeModeConversation.content(dropped))
        assertEquals(1, asked.size, session.summary())
        val gone = GptScript.of(session.gpt, "c3", SECOND_PROMPT, listOf(third), asked.single())

        session.talk.compact()
        val compacting = session.requests()
        val gpt = session.gpt
        val summary = session.rig.turn(session.talk, gpt.cutMidAnswer(CUT_WORDS), gpt.answer(SUMMARY_TEXT))

        val context = "the 23:23 shape over ${if (webSocket) "the websocket" else "HTTP"}"
        assertTrue(SUMMARY_TEXT in summary.text) {
            "the compaction was answered: ${summary.describe()}\n${session.summary()}"
        }
        session.assertInPlace(context, compacting, listOf(first, second), dropped = listOf(gone))
    }
}

/** A script GPT wrote and the client ran a tool for: the cell it runs in, the prompt it answered, GPT's reasoning and
 *  calls as the backend streamed them (its exec, and the wait on it when it yielded), and the client's tool call. */
private class GptScript(
    val cell: String,
    val prompt: String,
    val reasoning: List<JsonObject>,
    val calls: List<JsonObject>,
    val toolUse: String,
) {
    /** Where GPT's calls for this script sit in [items], or why they do not sit as GPT wrote them. */
    fun kept(items: List<JsonObject>): Pair<List<Int>, List<String>> {
        val problems = mutableListOf<String>()
        reasoning.filter { ours -> items.none { sameReasoning(it, ours) } }
            .forEach { problems += "$cell: GPT's reasoning ${JsonScalars.str(it, "id")} does not go out" }
        val at = calls.map { call -> items.indexOfFirst { kind(it) == kind(call) && callId(it) == callId(call) } }
        val results = calls.zip(at).mapNotNull { (call, index) -> result(items, call, index, problems) }
        val said = results.joinToString(" ") { it["output"].toString() }
        if (results.size == calls.size && "finished $cell" !in said) problems += "$cell: no result says it finished"
        if (STOPPED in said) problems += "$cell: a result says the head stopped it"
        if (at.filter { it >= 0 } != at.filter { it >= 0 }.sorted()) problems += "$cell: its calls go out out of order"
        if (items.any { kind(it) == "function_call" && callId(it) == toolUse }) {
            problems += "$cell: the client's $toolUse goes out as a plain tool call"
        }
        return at.filter { it >= 0 } to problems
    }

    /** Why [items] do not carry the part of this script Claude Code changed as Claude Code sent it: the client's tool
     *  call, with the result the client changed it to [text], where GPT's call that read that result stood. GPT's call
     *  before it, an exec that yielded and never read the result, is GPT's own and goes out as GPT wrote it. */
    fun fellBack(items: List<JsonObject>, text: String): List<String> {
        val problems = mutableListOf<String>()
        val at = items.indexOfFirst { kind(it) == "function_call" && callId(it) == toolUse }
        val output = items.drop(at + 1).firstOrNull { kind(it) == "function_call_output" && callId(it) == toolUse }
        if (at < 0) problems += "$cell: the client's changed $toolUse does not go out as its plain tool call"
        if (at >= 0 && text !in output?.get("output").toString()) {
            problems += "$cell: the client's $toolUse goes out without the result the client changed it to"
        }
        return problems + gptsOwn(items)
    }

    /** Why [items] do not carry GPT's side of a script whose result the client changed: the call that read the result
     *  is gone, and each call before it goes out as GPT wrote it, with its result. */
    private fun gptsOwn(items: List<JsonObject>): List<String> {
        val problems = mutableListOf<String>()
        val read = calls.last()
        if (items.any { kind(it) == kind(read) && callId(it) == callId(read) }) {
            problems += "$cell: GPT's ${callId(read)} goes out though the client changed the result it read"
        }
        calls.dropLast(1).forEach { call ->
            val placed = items.indexOfFirst { kind(it) == kind(call) && callId(it) == callId(call) }
            result(items, call, placed, problems)
        }
        return problems
    }

    /** Why [items] carry the part of this script Claude Code dropped with its turn: GPT's call that asked the client
     *  for the tool call Claude Code never ran. A call before it, an exec that yielded, never reached the client and
     *  is GPT's own past, so it may go out as GPT wrote it, as Codex keeps it. */
    fun gone(items: List<JsonObject>): List<String> {
        val asked = calls.last()
        return listOfNotNull(
            "$cell: GPT's call ${callId(asked)} goes out though Claude Code dropped its turn"
                .takeIf { items.any { kind(it) == kind(asked) && callId(it) == callId(asked) } },
        )
    }

    private fun result(items: List<JsonObject>, call: JsonObject, at: Int, problems: MutableList<String>): JsonObject? {
        val id = callId(call)
        if (at < 0) {
            problems += "$cell: GPT's ${JsonScalars.str(call, "name")} call $id does not go out"
            return null
        }
        if (words(items[at]) != words(call)) problems += "$cell: GPT's call $id goes out other than GPT wrote it"
        val result = items.drop(at + 1).firstOrNull { kind(it) == RESULT_KINDS[kind(call)] && callId(it) == id }
        if (result == null) problems += "$cell: GPT's call $id goes out with no result"
        return result
    }

    private fun sameReasoning(item: JsonObject, ours: JsonObject): Boolean = kind(item) == "reasoning" &&
        JsonScalars.str(item, "id") == JsonScalars.str(ours, "id") &&
        item["encrypted_content"] == ours["encrypted_content"]

    /** What GPT wrote in a call: the tool it named and what it gave it. */
    private fun words(call: JsonObject): List<Any?> =
        listOf(JsonScalars.str(call, "name"), call["input"], call["arguments"])

    companion object {
        fun of(gpt: ScriptedGpt, cell: String, prompt: String, answers: List<GptAnswer>, toolUse: String): GptScript {
            val items = answers.flatMap { gpt.outputs[it.id].orEmpty() }
            return GptScript(
                cell,
                prompt,
                items.filter { kind(it) == "reasoning" },
                items.filter { kind(it) in RESULT_KINDS },
                toolUse,
            )
        }

        fun kind(item: JsonObject): String? = JsonScalars.str(item, "type")

        fun callId(item: JsonObject): String? = JsonScalars.str(item, "call_id")
    }
}

/** One code-mode conversation on one head: GPT, what the backend read, and the client's side of it. */
private class InPlaceSession(
    val rig: CodeModeRig,
    val ledger: BackendLedger,
    val gpt: ScriptedGpt,
    val talk: CodeModeConversation,
) {
    private var prompt = JsonScalars.str(talk.messages.first(), "content").orEmpty()

    /** GPT writes a script in [cell] that asks the client to Read, after yielding and a wait on it when [yields]; the
     *  client runs the tool, and its result goes out with the next request. */
    suspend fun script(cell: String, yields: Boolean): GptScript {
        val answers = if (yields) {
            listOf(gpt.exec("cell:$cell yield read"), gpt.waitOn(cell))
        } else {
            listOf(gpt.exec("cell:$cell read"))
        }
        val toolUse = ledger.toolCall(talk, rig.turn(talk, *answers.toTypedArray()), rig)
        talk.result(toolUse, FILE_TEXT)
        return GptScript.of(gpt, cell, prompt, answers, toolUse)
    }

    /** GPT answers in words, and the client keeps the answer. */
    suspend fun answer(text: String): ClientAnswer {
        val answer = rig.turn(talk, gpt.answer(text))
        assertEquals(emptyList<String>(), talk.answered(answer)) { "GPT answered: ${answer.describe()}\n${summary()}" }
        return answer
    }

    fun user(text: String) {
        talk.user(text)
        prompt = text
    }

    /** One prompt GPT answered after a script (and a wait on it when [yields]), then a second prompt whose script
     *  asked the client to Read the same way, with the client's result kept for the next request. */
    suspend fun twoPrompts(yields: Boolean = true): Pair<GptScript, GptScript> {
        val first = script("c1", yields)
        answer(FIRST_REPLY)
        user(SECOND_PROMPT)
        return first to script("c2", yields)
    }

    fun requests(): Int = ledger.requestCount()

    fun summary(): String = ledger.summary(rig)

    /** Every request from [from] on carries each of [kept] as GPT wrote it, in GPT's order and after the prompt it
     *  answered, each of [changed] as the client's plain tool call with the result the client changed it to, and
     *  nothing of [dropped], whose turns Claude Code dropped. */
    fun assertInPlace(
        context: String,
        from: Int,
        kept: List<GptScript>,
        changed: Map<GptScript, String> = emptyMap(),
        dropped: List<GptScript> = emptyList(),
    ) {
        val problems = ledger.reads().drop(from).flatMap { read ->
            (problems(read.input, kept, changed) + dropped.flatMap { it.gone(read.input) })
                .map { "request ${read.at} (${read.transport}): $it" }
        }
        assertTrue(problems.isEmpty()) { "$context\n${problems.joinToString("\n")}\n${summary()}" }
    }

    fun problems(items: List<JsonObject>, kept: List<GptScript>, changed: Map<GptScript, String>): List<String> {
        val placed = kept.map { it to it.kept(items) }
        val order = placed.zipWithNext().mapNotNull { (earlier, later) ->
            val last = earlier.second.first.maxOrNull() ?: return@mapNotNull null
            val first = later.second.first.minOrNull() ?: return@mapNotNull null
            "${later.first.cell} goes out before ${earlier.first.cell}, which GPT wrote first".takeIf { first < last }
        }
        val prompts = placed.mapNotNull { (script, at) ->
            val asked = items.indexOfLast { prompt(it) && script.prompt in it.toString() }
            val first = at.first.minOrNull() ?: return@mapNotNull null
            "${script.cell} goes out before the prompt it answered".takeIf { asked < 0 || first < asked }
        }
        return placed.flatMap { it.second.second } + order + prompts +
            changed.flatMap { (script, text) -> script.fellBack(items, text) }
    }

    /** A person's prompt as the head sends it: a user message, which a Responses input may write without a type. */
    private fun prompt(item: JsonObject): Boolean =
        JsonScalars.str(item, "role") == "user" && GptScript.kind(item).let { it == null || it == "message" }

    companion object {
        suspend fun start(
            heads: ScriptedHeads,
            tmp: Path,
            webSocket: Boolean,
            name: String,
            model: String? = null,
        ): InPlaceSession {
            val gpt = ScriptedGpt()
            val ledger = BackendLedger(heads, gpt)
            val rig = if (model == null) {
                CodeModeRig.start(heads, tmp, ledger, webSocket)
            } else {
                CodeModeRig.start(heads, tmp, ledger, webSocket, model)
            }
            return InPlaceSession(rig, ledger, gpt, CodeModeConversation(name, OPENING))
        }
    }
}

/** A generated conversation: one to three prompts, each answered by a script that may yield and be waited on before
 *  it asks the client to Read, and then by GPT's words. One round may be interrupted (an overload before any output,
 *  after reasoning or once the answer began, a websocket close before output, or a compaction between a call and its
 *  run, which drops the call in flight), and before one request Claude Code may change the result of a script it ran
 *  earlier: clear it, as its context management clears old tool results, or send other text. Every request carries
 *  each earlier script as GPT wrote it, and the changed one as Claude Code sent it. */
private class GeneratedHistory(private val session: InPlaceSession, private val random: Random, private val run: Int) {
    val problems = mutableListOf<String>()
    private val scripts = mutableListOf<GptScript>()
    private val dropped = mutableListOf<GptScript>()
    private val changed = LinkedHashMap<GptScript, String>()
    private val prompts = 1 + random.nextInt(MOST_SCRIPTS)
    private val interrupted = random.nextInt(prompts * 2)
    private val cut = InPlaceCut.entries[random.nextInt(InPlaceCut.entries.size)]
    private val changes = random.nextInt(prompts * 2 + 1)
    private val gpt = session.gpt

    /** Plays the conversation, checks every request it sent, and says what it did. */
    suspend fun play(): String {
        val story = mutableListOf("prompts=$prompts $cut@round$interrupted change@round$changes")
        var at = 0
        var prompt = 0
        var going = true
        while (going && prompt < prompts) {
            val current = prompt++
            val script = round(at++, story) { interrupt -> script(current, interrupt) }
            if (script != null) scripts += script
            going = script != null && round(at++, story) { interrupt -> words(current, interrupt) } != null
        }
        return story.joinToString(", ")
    }

    /** One request and its answer, preceded by Claude Code's change and interrupted when the dice said so; null when
     *  the conversation ends there. A compaction comes only between a call and its run, as Claude Code's did. */
    private suspend fun <T> round(at: Int, story: MutableList<String>, step: suspend (Boolean) -> T?): T? {
        if (at == changes) change(story)
        val interrupt = at == interrupted
        val compacts = cut == InPlaceCut.COMPACTION && at % 2 == 1
        if (interrupt && compacts) {
            compact(story)
            return null
        }
        val before = session.requests()
        val done = step(interrupt && cut != InPlaceCut.COMPACTION)
        inspect(before)
        if (done == null) story += "round $at ended the conversation"
        return done
    }

    private suspend fun script(prompt: Int, interrupt: Boolean): GptScript? {
        if (prompt > 0) session.user(prompt(prompt))
        val cell = "g$run-$prompt"
        val answers = if (random.nextBoolean()) {
            listOf(gpt.exec("cell:$cell yield read"), gpt.waitOn(cell))
        } else {
            listOf(gpt.exec("cell:$cell read"))
        }
        val answer = session.rig.turn(session.talk, *planned(answers, interrupt).toTypedArray())
        val toolUse = session.talk.answered(answer).singleOrNull() ?: return null
        session.talk.result(toolUse, "$FILE_TEXT $cell")
        return GptScript.of(gpt, cell, prompt(prompt), answers, toolUse)
    }

    private suspend fun words(prompt: Int, interrupt: Boolean): ClientAnswer? {
        val answers = planned(listOf(gpt.answer("synthetic answer $run-$prompt")), interrupt)
        val answer = session.rig.turn(session.talk, *answers.toTypedArray())
        return answer.takeIf { session.talk.answered(it).isEmpty() && it.error == null }
    }

    private fun prompt(prompt: Int): String =
        if (prompt == 0) "$OPENING $run" else "Go on with synthetic step $run-$prompt."

    /** Claude Code changes the result of a script whose result it already sent: it clears it, or sends other text. */
    private fun change(story: MutableList<String>) {
        val sent = scripts.dropLast(1).filter { it !in changed }
        if (sent.isEmpty()) return
        val script = sent[random.nextInt(sent.size)]
        val text = if (random.nextBoolean()) CLEARED else "$CHANGED_TEXT ${script.cell}"
        session.talk.changeResult(script.toolUse, text)
        changed[script] = text
        story += if (text == CLEARED) "cleared ${script.cell}" else "changed ${script.cell}"
    }

    /** Claude Code compacts between a call and its run: the answer with the call in flight and its result are dropped,
     *  and the previous request goes out again with the summary instruction. Its stream drops once the summary
     *  began. */
    private suspend fun compact(story: MutableList<String>) {
        session.talk.dropLastToolTurn()
        dropped += scripts.removeAt(scripts.lastIndex)
        session.talk.compact()
        val before = session.requests()
        val summary = session.rig.turn(session.talk, gpt.cutMidAnswer(CUT_WORDS), gpt.answer(SUMMARY_TEXT))
        inspect(before)
        story += "compacted: ${SUMMARY_TEXT in summary.text} error: ${summary.error?.type}"
    }

    private fun planned(answers: List<GptAnswer>, interrupt: Boolean): List<GptAnswer> {
        if (!interrupt) return answers
        val round = random.nextInt(answers.size)
        val failing = when (cut) {
            InPlaceCut.OVERLOAD_BEFORE_OUTPUT -> gpt.overloadBeforeOutput()
            InPlaceCut.OVERLOAD_AFTER_REASONING -> gpt.overloadAfterReasoning()
            InPlaceCut.OVERLOAD_AFTER_TEXT -> gpt.overloadAfterText(CUT_WORDS)
            InPlaceCut.CLOSE_BEFORE_OUTPUT -> gpt.closeBeforeOutput()
            InPlaceCut.COMPACTION -> return answers
        }
        return answers.take(round) + failing + answers.drop(round)
    }

    /** The requests since [before] carry every earlier script in place, each changed one as Claude Code sent it, and
     *  nothing of a script whose turn Claude Code dropped. */
    private fun inspect(before: Int) {
        val kept = scripts.filter { it !in changed }
        session.ledger.reads().drop(before).forEach { read ->
            problems += (session.problems(read.input, kept, changed) + dropped.flatMap { it.gone(read.input) })
                .map { "request ${read.at}: $it" }
        }
    }
}

/** What interrupts one round of a generated conversation. */
private enum class InPlaceCut {
    OVERLOAD_BEFORE_OUTPUT,
    OVERLOAD_AFTER_REASONING,
    OVERLOAD_AFTER_TEXT,
    CLOSE_BEFORE_OUTPUT,
    COMPACTION,
}
